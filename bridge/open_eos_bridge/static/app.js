(() => {
  "use strict";

  const connectionGuidance = globalThis.OpenEOSConnectionGuidance;
  if (!connectionGuidance) throw new Error("Open EOS connection guidance module is unavailable.");
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
  const mediaLibrary = globalThis.OpenEOSMediaLibrary;
  if (!mediaLibrary) throw new Error("Open EOS media library module is unavailable.");
  const mediaBatch = globalThis.OpenEOSMediaBatch;
  if (!mediaBatch) throw new Error("Open EOS media batch module is unavailable.");
  const mediaBatchDestination = globalThis.OpenEOSMediaBatchDestination;
  if (!mediaBatchDestination) throw new Error("Open EOS media batch destination module is unavailable.");

  const mediaTransferHistory = globalThis.OpenEOSMediaTransferHistory;
  const mediaHistoryStore = mediaTransferHistory?.createHistoryStore();
  const mediaAutoImport = globalThis.OpenEOSMediaAutoImport;

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
    MEDIA_UPLOAD: "MEDIA_UPLOAD",
    MEDIA_PROTECT: "MEDIA_PROTECT",
    MEDIA_RATING: "MEDIA_RATING",
    MEDIA_ROTATE: "MEDIA_ROTATE",
    MEDIA_ARCHIVE: "MEDIA_ARCHIVE",
    MEDIA_DELETE: "MEDIA_DELETE",
    CAMERA_CLOCK_SYNC: "CAMERA_CLOCK_SYNC",
    DIRECTORY_CONTROL: "DIRECTORY_CONTROL",
    FILE_NAMING_CONTROL: "FILE_NAMING_CONTROL",
    SENSOR_CLEANING: "SENSOR_CLEANING",
    CAMERA_SLEEP: "CAMERA_SLEEP",
  };
  const CORE_SETTINGS = ["iso", "shutter", "aperture", "whitebalance"];
  const LANGUAGE_KEY = "open-eos-control-language";
  const CCAPI_URL_KEY = "open-eos-control-ccapi-url";
  const CCAPI_USERNAME_KEY = "open-eos-control-ccapi-username";
  const MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024;
  const MAX_MEDIA_PREVIEW_BYTES = 32 * 1024 * 1024;
  const LATEST_MEDIA_LIMIT = 8;
  const LATEST_MEDIA_RETRY_DELAYS_MILLIS = [250, 750, 1500];
  const MEDIA_PAGE_SIZE = 72;
  const MAX_MEDIA_THUMBNAIL_CACHE_ITEMS = 96;
  const LOCAL_VIDEO_RENDER_INTERVAL_MILLIS = 100;

  const messages = {
    en: {
      desktopControl: "Desktop control",
      language: "Language",
      auto: "Auto",
      connectCamera: "Connect camera",
      connectionBridgeHelp: "This page uses the Bridge running on your computer to reach the camera. Keep that Bridge running.",
      connectionCheckBridge: "Check Bridge again",
      connectionTokenHelp: "Use the token configured for this computer’s Bridge, if required.",
      connectionUsbHelp: "Turn on the camera, connect a USB data cable, and close other camera-control apps. USB needs an available libgphoto2 or configured Canon EDSDK engine on this computer.",
      connectionCcapiHelp: "Enable CCAPI on a compatible camera and connect this computer to a network that can reach it. Use the address and port shown by the camera. Camera Connect pairing alone does not enable CCAPI.",
      connectionOriginHelp: "Use only http:// or https://, the camera address, and its port. Do not add /ccapi, credentials, a query, or a fragment.",
      connectionCredentialHelp: "Enter the camera’s CCAPI username and password only if the camera requires them. These are separate from the Bridge token.",
      connectionInvalidOrigin: "Enter a camera origin such as http://192.168.1.2:8080. Remove /ccapi, credentials, query parameters, and fragments.",
      connectionAddressRequired: "Enter the CCAPI address shown by the camera to enable Connect.",
      connectionUsbEngine: "No USB camera engine is available. Check the Bridge’s libgphoto2 or Canon EDSDK setup on this computer, then check Bridge again.",
      connectionCcapiEngine: "The Bridge’s CCAPI engine is unavailable. Check the running Bridge and try Check Bridge again.",
      connectionBridgeAuth: "The Bridge rejected its token. Enter the token configured for this computer’s Bridge, then retry.",
      connectionBridgeAccess: "The Bridge denied access. Check whether you opened its local page and whether its access settings allow this connection.",
      connectionBridgeEndpoint: "This Bridge API endpoint was not found. Check that this page and the running Bridge belong to the same installation, then check Bridge again.",
      connectionBridgeCheckFailed: "Bridge check failed. Follow the guidance below, then check again.",
      connectionBridgeNetwork: "This page could not reach the Bridge. Keep the Bridge running and check the computer connection, then check Bridge again. The browser did not identify the exact cause.",
      connectionCameraAuth: "The camera rejected CCAPI authentication. Check its CCAPI username, password, and authentication settings, then retry Connect.",
      connectionCameraEndpoint: "The camera’s CCAPI discovery endpoints were not found. Check CCAPI support, enable it on the camera, and verify its address and port before retrying.",
      connectionCameraHttp: "The camera returned an HTTP error during CCAPI discovery. Check its screen and CCAPI setup, then retry. Authentication failure was not established.",
      connectionCameraTls: "The Bridge could not establish the camera’s TLS connection. Verify the camera’s HTTPS address and certificate setup before retrying.",
      connectionCameraHost: "The Bridge could not resolve the camera’s host name. Verify its current address on the camera and this computer’s network, then retry.",
      connectionCameraTimeout: "The camera’s CCAPI request timed out. Check that the camera is awake and reachable from this computer, then retry.",
      connectionCameraNetwork: "The Bridge could not complete the camera network connection. Check camera power, network, address, and port, then retry.",
      connectionCameraDiscovery: "CCAPI discovery did not complete; the exact cause is unknown. Check CCAPI is enabled and verify the camera’s address, port, and network before retrying.",
      connectionUnknownFailure: "The connection request failed without a verified recovery reason. Check the Bridge and camera setup, then retry explicitly.",
      connectionScanIdle: "Choose Scan after checking the USB connection.",
      connectionScanEmpty: "Scan finished with no cameras. Check the USB data cable, camera power and USB mode, and close other camera apps; then choose Scan again.",
      connectionScanFailed: "Scan failed. Follow the guidance below, then choose Scan again.",
      connectionScanCancelled: "Scan cancelled. Choose Scan again when you are ready.",
      connectionScanReady: "Choose a detected camera, then Connect.",
      connectionCcapiReady: "Check the camera’s CCAPI setup, then choose Connect. No camera command is sent until you connect.",
      connectionScanAgain: "Scan again",
      connectionCancelScan: "Cancel scan",
      connectionRetry: "Retry connection",
      connectionEditToken: "Edit Bridge token",
      connectionEditAddress: "Edit camera address",
      connectionEditCredentials: "Edit camera credentials",
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
      latestMedia: "Latest media",
      latestMediaEmpty: "No recent media",
      latestMediaLoading: "Updating latest media",
      openLatestMedia: "Open latest media",
      previouslyVisibleMedia: "Previously visible media",
      openPreviousMedia: "Open previously visible media",
      latestMediaSearching: "Looking for newly visible media…",
      latestVideoSearching: "Recording stopped. Looking for newly visible video…",
      latestVideoNotReady: "New video is not visible yet. Check again without sending another REC command.",
      latestVideoReadFailed: "The media list could not be read. Check again without sending another REC command.",
      latestVideoFound: "A newly visible video was found; this does not prove it came from that recording.",
      recordingStopUnconfirmed: "Recording stop is not confirmed by the returned camera status.",
      recordingStartUnconfirmed: "The returned camera status does not confirm recording started.",
      recordingCommandUnconfirmed: "The recording result is unknown. Use Stop; refreshed cached status cannot confirm the camera stopped.",
      recordingStatusUnconfirmed: "The recording command was accepted, but status is unconfirmed. Refresh status; only Stop is available meanwhile.",
      stopUnconfirmedRecording: "Stop recording (status unconfirmed)",
      latestMediaNotReady: "New media is not visible yet. Check again without taking another photo.",
      latestMediaReadFailed: "The camera's media list could not be read. Check again without taking another photo.",
      latestMediaFound: "Newly visible media found. Open the preview to confirm it.",
      latestMediaReviewLimit: "Checks up to 8 items returned by the camera. A newly visible item may still be an older file.",
      latestMediaRetry: "Check again",
      record: "Record",
      stopRecording: "Stop recording",
      ready: "Ready",
      busy: "Working",
      autofocus: "AF-ON",
      shutterAF: "Shutter AF",
      shutterAFOn: "Shutter request asks for autofocus.",
      shutterAFOff: "Shutter request does not ask for autofocus. Camera or lens settings may still focus.",
      shutterAFUnavailable: "This session cannot request AF off. Enable shutter AF or reconnect.",
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
      liveViewSourceMultipart: "Multipart JPEG",
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
      createCaptureDirectory: "Create capture directory",
      createCaptureDirectoryHint: "Leave blank for the camera default, or enter exactly 5 uppercase letters, numbers, or underscores.",
      directoryNamePlaceholder: "EOSXX or blank",
      directoryCreated: "Created {name}",
      create: "Create",
      fileNaming: "File naming",
      fileNamingPhotoSummary: "Still filename: {mode}",
      fileNamingVideoSummary: "Index {index} · Reel {reel} · Clip {clip}",
      fileNamingPresetCode: "Preset code",
      stillUserSetting1: "User setting 1",
      stillUserSetting2: "User setting 2",
      movieIndex: "Movie index",
      movieReelNumber: "Reel number",
      movieClipNumber: "Clip number",
      movieUserDefined: "Movie user-defined name",
      fileNamingRule: "Use Canon's exact uppercase ASCII length and range.",
      apply: "Apply",
      fileNamingUpdated: "File naming updated",
      ownername: "Owner name",
      artist: "Artist",
      copyright: "Copyright",
      nickname: "Nickname",
      textMetadataRule: "Printable ASCII, up to 255 bytes.",
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
      previousShutterWarning: "Recording, shutter, or autofocus may still be active after the previous connection. Check the camera and stop any active control before using it again.",
      confirmCameraChecked: "I've checked the camera",
      retryBulbStop: "Retry camera stop",
      bulbReleaseConfirmed: "Camera stop confirmed",
      bulbReleaseUnconfirmed: "Camera stop unconfirmed. The shutter or autofocus may still be active. Retry stop or check the camera.",
      bulbDisconnectWarning: "Connection closed locally; the camera stop is unconfirmed. Check the camera before reconnecting.",
      autofocusComplete: "Focus command accepted; focus is not confirmed",
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
      soundrecordingmodeintmic: "Internal microphone mode",
      soundrecordingmodeextmic: "External microphone mode",
      soundrecordingmodeacc: "Accessory microphone mode",
      soundrecordinglevel: "Sound recording level",
      soundrecordinglevelintmic: "Internal microphone level",
      soundrecordinglevelextmic: "External microphone level",
      soundrecordinglevelacc: "Accessory microphone level",
      windfilter: "Wind filter",
      windfilterintmic: "Internal microphone wind filter",
      windfilterextmic: "External microphone wind filter",
      windfilteracc: "Accessory microphone wind filter",
      attenuator: "Attenuator",
      attenuatorintmic: "Internal microphone attenuator",
      attenuatorextmic: "External microphone attenuator",
      attenuatoracc: "Accessory microphone attenuator",
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
      movieQualityFine: "Fine",
      zoomspeed: "Power zoom speed",
      autopoweroff: "Auto power off",
      beep: "Beep",
      displayoff: "Auto display off",
      capturetarget: "Capture target",
      capturestorage: "Recording card",
      cardselectionstillimage: "Still-image card",
      cardselectionmovie: "Movie card",
      directoryselection: "Capture directory",
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
      mediaRecentCount: "Latest {count} media item(s)",
      mediaRecentMoreCount: "Latest {count} media item(s) - more on card",
      mediaEmpty: "No media was reported by the camera",
      mediaFilteredCount: "{visible} of {total} media items",
      mediaLoadingCount: "Loading camera media - {count} item(s) currently shown",
      mediaFailedCount: "Media refresh failed - showing {count} previous item(s)",
      mediaNotLoadedCount: "Media not loaded",
      mediaScope: "Media range",
      mediaRecent: "Recent",
      mediaFullCard: "Full card",
      mediaFilter: "Filter media",
      mediaAll: "All",
      mediaPhotos: "Photos",
      mediaVideos: "Videos",
      mediaSort: "Sort media",
      mediaCameraOrder: "Camera order",
      mediaNewest: "Newest first",
      mediaOldest: "Oldest first",
      mediaName: "File name",
      mediaPages: "Media pages",
      previousMediaPage: "Previous media page",
      nextMediaPage: "Next media page",
      mediaPageStatus: "{start}-{end} of {total}",
      mediaUnknownDate: "Unknown date",
      mediaFolderFilter: "Camera folder filter",
      mediaFolderClear: "Clear folder filter",
      mediaFolderAll: "All loaded folders",
      mediaFolderUnknown: "Unknown folder",
      mediaFolderHelp: "Uses only folder information already supplied by the camera backend. No directory or metadata requests are added. Folder labels do not establish physical card slots.",
      mediaFolderActive: "{matched} of {total} loaded items match; {unknown} have unknown folders. This is not a complete folder inventory.",
      mediaFolderMissing: "Previously selected folder (not currently loaded)",
      mediaFolderDuplicate: "distinct folder {index}",
      mediaRatingFilter: "Rating filter",
      mediaRatingClear: "Clear rating filter",
      mediaRatingAll: "All ratings",
      mediaRatingUnrated: "Unrated (known 0)",
      mediaRatingOne: "1 star and above",
      mediaRatingTwo: "2 stars and above",
      mediaRatingThree: "3 stars and above",
      mediaRatingFour: "4 stars and above",
      mediaRatingFive: "5 stars",
      mediaRatingUnknown: "Unknown rating",
      mediaRatingHighest: "Highest rating first",
      mediaRatingLowest: "Lowest rating first",
      mediaRatingHelp: "Uses loaded ratings only. Unknown is different from unrated (0). No additional camera metadata is requested; Recent or incomplete lists do not cover the whole card.",
      mediaRatingActive: "{matched} of {total} loaded items match. {unknown} loaded items have unknown ratings. Only loaded ratings are filtered or sorted.",
      mediaDateRange: "Media date range",
      mediaDateFrom: "From",
      mediaDateTo: "To",
      mediaDateApply: "Apply",
      mediaDateCancel: "Cancel",
      mediaDateClear: "Clear date range",
      mediaDateHelp: "Filters loaded media only, without reading more dates from the camera. Some connections do not report dates. Dates may be file modification dates, not capture dates.",
      mediaDateZone: "Display time zone: {zone}. Date-only values keep their reported day.",
      mediaDateActive: "{start} to {end}: {matched} of {total} loaded items match. {unknown} loaded items have unknown dates and are excluded.",
      mediaDateRequired: "Enter both dates.",
      mediaDateInvalid: "Enter valid calendar dates.",
      mediaDateReversed: "The end date must be on or after the start date.",
      mediaNoMatches: "No loaded media matches these filters. Clear the date, rating or folder filter, or change the media type.",
      mediaSelect: "Select {name}",
      mediaSelectFiltered: "Select all loaded matches",
      mediaSelectionClear: "Clear selection",
      mediaSelectionSummary: "{selected} selected · {hidden} hidden by filters · {notLoaded} not currently loaded",
      mediaSelectionHelp: "Selects loaded matches across pages, not unseen files on the card. Selection is kept when filters or scope change. Hidden and no-longer-loaded selections are included in the batch.",
      mediaHistoryTitle: "Batch saving history",
      mediaHistoryOpen: "Batch saving history",
      mediaHistoryHelp: "Worklists and other started attempts share 100 rows in this browser profile and Bridge address. Queued rows have no confirmed attempt. Saved means the browser confirmed close at that time, not that the file still exists. Unconfirmed attempts may still be running in another tab. Names are display labels, not camera identity. Nothing reconnects or retries automatically; single-file browser downloads are excluded.",
      mediaHistoryWarning: "Saving history is unavailable or incomplete. This does not change the actual file transfer result. Inspect the destination before retrying.",
      mediaHistoryLoading: "Loading history…",
      mediaHistoryUnavailable: "History could not be read. Existing records have not been cleared.",
      mediaHistoryEmpty: "No worklists or recorded attempts.",
      mediaHistoryCount: "{count} worklist rows and other attempts",
      mediaHistoryQueued: "Queued; no confirmed attempt",
      mediaHistoryWorklistPosition: "Worklist {created} · item {position} of {total}",
      mediaHistoryLegacyAttempt: "Separate attempt; no complete worklist",
      mediaWorklistAdmissionFailed: "The next worklist attempt could not be recorded. Saving stopped before opening its file or requesting the original. Inspect history before retrying.",
      mediaWorklistAdmissionUnknown: "The worklist save could not be confirmed. No files were opened or originals requested. Inspect history before starting a new batch.",
      mediaHistoryCompleted: "Saved at the time of transfer",
      mediaHistoryFailed: "Failed",
      mediaHistoryUnconfirmed: "Result unconfirmed; check the destination",
      mediaHistoryLeftover: "A file may remain or its final state is uncertain.",
      mediaHistoryClear: "Clear history…",
      mediaHistoryClearQuestion: "Clear these worklists and history records only? Destination files and current transfers are unchanged. Late results from existing batches will not restore cleared records.",
      mediaHistoryClearConfirm: "Clear records",
      mediaHistoryClearCancel: "Keep records",
      mediaHistoryClearFailed: "History could not be cleared. Records may remain; reload history to check.",
      mediaBatchStart: "Save selected originals to folder…",
      mediaBatchUnavailable: "Batch saving needs a browser with folder access. You can still download originals individually.",
      mediaBatchTitle: "Original file batch",
      mediaBatchChoosing: "Choose a folder. A new isolated batch folder will be created inside it.",
      mediaBatchDestination: "Destination: {name}",
      mediaBatchSummary: "{completed} saved · {failed} failed · {uncertain} uncertain · {notStarted} not started · {queued} queued · {running} running",
      mediaBatchCancel: "Cancel remaining files",
      mediaBatchCancelling: "Cancelling. The current camera read or file close may still finish; later files will not start.",
      mediaBatchNotice: "Files are saved one at a time. Saved means the browser confirmed the file was closed. Cancellation may leave partial files in the batch folder; camera reads may continue in the Bridge.",
      mediaBatchLeftovers: "{count} file(s) may be incomplete or have an uncertain result in the batch folder. Inspect them before using or removing them.",
      mediaBatchDirectoryMayRemain: "An empty batch folder may remain at the chosen destination.",
      mediaBatchQueued: "Queued",
      mediaBatchRunning: "Saving",
      mediaBatchCompleted: "Saved",
      mediaBatchFailed: "Failed",
      mediaBatchUncertain: "Save result uncertain; inspect the destination",
      mediaBatchNotStarted: "Not started",
      mediaBatchFinished: "Batch finished. Review the per-file results.",
      mediaBatchStopped: "Batch stopped. Saved files are retained.",
      mediaAutoImportTitle: "Foreground JPEG auto-import",
      mediaAutoImportStart: "Enable JPEG import to folder…",
      mediaAutoImportStop: "Stop JPEG import",
      mediaAutoImportPurpose: "Choose a folder to save new JPEGs while this tab stays visible. Existing photos, RAW and video are excluded.",
      mediaAutoImportHelp: "Limits and file verification",
      mediaAutoImportEvidence: "Two matching complete CCAPI inventories establish the existing files. Only JPEG identities observed afterward are considered; this does not prove which device or app took the photo. Each pending JPEG needs two fresh matching positive byte sizes at least one second apart. A single original request must match that size exactly before the browser confirms file close. Camera timestamps and capture events are not proof of a completed file.",
      mediaAutoImportLimits: "CCAPI with media browsing and downloads only; unavailable during recording or Bulb. Foreground only, with a complete inventory about every 5 seconds. Limits: 1,000 entries per inventory, 4,096 known identities, 20 pending JPEGs, 30 seconds to confirm each file, and the latest 100 results. Manual camera work takes priority between reads.",
      mediaAutoImportNotice: "Stop, hiding this tab, leaving the page, disconnecting, or losing support ends this run; returning does not restart it. A read or file close already in progress may still finish, and Bridge camera reads may continue after browser cancellation. Partial files or an empty folder may remain. Check the destination before retrying. Folder access is chosen each time and is never restored automatically.",
      mediaAutoImportUnavailable: "Auto-import needs folder access and secure UUID support in this browser. Single-file manual downloads remain available.",
      mediaAutoImportSummary: "{saved} saved · {pending} pending · {known} known identities · {results} recent results (maximum 100)",
      mediaAutoImportOff: "Off. Enable explicitly to begin a new run.",
      mediaAutoImportChoosing: "Choose a folder. No camera inventory or output folder has started yet.",
      mediaAutoImportBaseline: "Confirming the existing-file baseline; existing photos will not be imported.",
      mediaAutoImportWatching: "Enabled while this page is visible. Waiting for new, stable JPEG files and yielding to manual work.",
      mediaAutoImportInventory: "Reading a complete camera inventory…",
      mediaAutoImportMetadata: "Checking a pending JPEG's fresh size…",
      mediaAutoImportSaving: "Saving one verified JPEG original…",
      mediaAutoImportStopping: "Stopping. An active read or file close may still finish; no later files will start.",
      mediaAutoImportStopped: "Stopped. Review saved and uncertain results before starting a new run.",
      mediaAutoImportUserStopped: "Stopped by you. Starting again creates a new baseline.",
      mediaAutoImportBackgrounded: "The page left the foreground. Enable again to start a new run.",
      mediaAutoImportDisconnected: "The owning camera session ended. Enable again after connecting.",
      mediaAutoImportCameraUnavailable: "CCAPI support changed, recording started, or Bulb became active. This run has stopped.",
      mediaAutoImportBaselineChanged: "The two baseline inventories differed. No existing files were imported; wait for the camera to settle and enable again.",
      mediaAutoImportLimit: "An inventory, identity, or pending-file limit was reached. Review the destination and enable again only when ready.",
      mediaAutoImportDisappeared: "A pending JPEG disappeared from the complete inventory. Its result is unconfirmed.",
      mediaAutoImportMetadataDeadline: "A pending JPEG did not obtain stable fresh size evidence within 30 seconds. It was not saved.",
      mediaAutoImportInventoryUnavailable: "The Bridge could not confirm a complete bounded inventory. Import stopped without trusting a partial or legacy response.",
      mediaAutoImportMetadataUnavailable: "Fresh matching metadata could not be confirmed. No original was requested for this candidate.",
      mediaAutoImportUnconfirmed: "The save result is unconfirmed. Inspect the destination; do not assume the camera read was cancelled.",
      mediaAutoImportFailed: "The original request or save failed. Inspect any partial file before retrying.",
      mediaAutoImportDestinationUnavailable: "The selected folder could not be prepared. Enable again to choose a writable folder.",
      mediaAutoImportUnknown: "Import stopped for an unrecognized reason ({reason}). Inspect the destination before retrying.",
      mediaAutoImportProtocol: "Required strict Bridge response evidence is unavailable or inconsistent.",
      mediaAutoImportBusy: "Manual camera work has priority.",

      manageMedia: "Manage {name}",
      mediaDetails: "Media details",
      closeMediaDetails: "Close media details",
      loadingMediaDetails: "Reading metadata from the camera",
      protection: "Protection",
      protect: "Protect",
      unprotect: "Unprotect",
      rating: "Rating",
      ratingOff: "Off",
      ratingValue: "{value} stars",
      rotation: "Display rotation",
      rotationValue: "{value} degrees",
      archive: "Archive",
      unarchive: "Unarchive",
      mediaMetadataUpdated: "Updated {name}",
      mediaThumbnail: "Thumbnail for {name}",
      previewMedia: "Preview {name}",
      closeMediaPreview: "Close media preview",
      mediaPreviewPosition: "{position} of {total}",
      openMediaDetails: "Open media details",
      resetMediaZoom: "Reset zoom",
      loadingPreview: "Loading camera preview",
      previewUnavailable: "The camera preview could not be displayed.",
      videoPlaybackUnavailable: "This camera video could not be played.",
      videoPlaybackCodecUnsupported: "This browser cannot decode the {container} camera video. R6 Mark III 10-bit or 4:2:2 recordings may require compatible editing software.",
      videoPlaybackStorageUnavailable: "There is not enough free space to prepare this {container} camera video. Free some storage or download the original.",
      videoPlaybackTransportUnavailable: "This {container} camera video could not be played. Check the camera connection and try again.",
      tryVideoAgain: "Try video again",
      downloadOriginal: "Download original",
      previousMedia: "Previous media",
      nextMedia: "Next media",
      download: "Download",
      preparingDownload: "Preparing camera download",
      downloading: "Downloading {name}",
      downloadProgress: "{transferred} of {total} ({percent}%)",
      downloadProgressUnknown: "{transferred} transferred",
      cancelDownload: "Cancel download",
      cancellingDownload: "Cancelling download",
      downloadCancelled: "Download cancelled",
      downloaded: "Downloaded {name}",
      uploadMedia: "Upload media",
      uploading: "Uploading {name}",
      uploadProgress: "{transferred} of {total} ({percent}%)",
      cancelUpload: "Cancel upload",
      cancellingUpload: "Cancelling upload",
      uploadCancelled: "Upload cancelled",
      uploaded: "Uploaded {name}",
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
      fileNaming: "檔名設定",
      fileNamingPhotoSummary: "相片檔名：{mode}",
      fileNamingVideoSummary: "索引 {index} · Reel {reel} · Clip {clip}",
      fileNamingPresetCode: "預設代碼",
      stillUserSetting1: "自訂設定 1",
      stillUserSetting2: "自訂設定 2",
      movieIndex: "影片索引",
      movieReelNumber: "Reel 編號",
      movieClipNumber: "Clip 編號",
      movieUserDefined: "影片自訂名稱",
      fileNamingRule: "請使用 Canon 規定長度與範圍的大寫 ASCII 字元。",
      apply: "套用",
      fileNamingUpdated: "已更新檔名設定",
      ownername: "擁有者名稱",
      artist: "藝術家",
      copyright: "著作權",
      nickname: "暱稱",
      textMetadataRule: "可列印 ASCII，最多 255 bytes。",
      desktopControl: "電腦相機控制",
      language: "語言",
      auto: "自動",
      connectCamera: "連接相機",
      connectionBridgeHelp: "此頁面透過電腦上的 Bridge 連接相機。請保持 Bridge 執行。",
      connectionCheckBridge: "重新檢查 Bridge",
      connectionTokenHelp: "若 Bridge 要求驗證，請輸入此電腦 Bridge 設定的 token。",
      connectionUsbHelp: "開啟相機、接上可傳輸資料的 USB 線，並關閉其他相機控制程式。此電腦需有可用的 libgphoto2 或已設定的 Canon EDSDK 引擎。",
      connectionCcapiHelp: "在支援的相機上啟用 CCAPI，並讓電腦連上可存取相機的網路。使用相機顯示的位址與連接埠。只完成 Camera Connect 配對不代表已啟用 CCAPI。",
      connectionOriginHelp: "只填 http:// 或 https://、相機位址與連接埠。請勿加入 /ccapi、帳密、查詢參數或片段。",
      connectionCredentialHelp: "僅在相機要求驗證時填入相機的 CCAPI 帳密；這與 Bridge token 是兩組不同的資料。",
      connectionInvalidOrigin: "請填入相機原始位址，例如 http://192.168.1.2:8080，並移除 /ccapi、帳密、查詢參數與片段。",
      connectionAddressRequired: "填入相機顯示的 CCAPI 位址後，即可按連線。",
      connectionUsbEngine: "目前沒有可用的 USB 相機引擎。請檢查此電腦 Bridge 的 libgphoto2 或 Canon EDSDK 設定，再重新檢查 Bridge。",
      connectionCcapiEngine: "Bridge 的 CCAPI 引擎無法使用。請檢查正在執行的 Bridge，再按重新檢查 Bridge。",
      connectionBridgeAuth: "Bridge 拒絕了 token。請填入此電腦 Bridge 設定的 token，再重試。",
      connectionBridgeAccess: "Bridge 拒絕存取。請確認是否開啟本機的 Bridge 頁面，以及其存取設定是否允許此連線。",
      connectionBridgeEndpoint: "找不到此 Bridge API 端點。請確認此頁面與執行中的 Bridge 來自同一份安裝，再重新檢查 Bridge。",
      connectionBridgeCheckFailed: "Bridge 檢查失敗。請依下方說明處理後，再重新檢查。",
      connectionBridgeNetwork: "此頁面無法連上 Bridge。請保持 Bridge 執行並檢查電腦連線，再重新檢查 Bridge。瀏覽器未提供確切原因。",
      connectionCameraAuth: "相機拒絕了 CCAPI 驗證。請檢查相機的 CCAPI 帳號、密碼與驗證設定，再重試連線。",
      connectionCameraEndpoint: "找不到相機的 CCAPI 探索端點。請確認相機支援並已啟用 CCAPI，核對位址與連接埠後再重試。",
      connectionCameraHttp: "相機在 CCAPI 探索時回傳 HTTP 錯誤。請檢查相機畫面與 CCAPI 設定後重試；目前無法確認是驗證失敗。",
      connectionCameraTls: "Bridge 無法建立相機的 TLS 連線。請核對相機的 HTTPS 位址與憑證設定後重試。",
      connectionCameraHost: "Bridge 無法解析相機主機名稱。請核對相機目前顯示的位址與此電腦的網路後重試。",
      connectionCameraTimeout: "相機的 CCAPI 請求逾時。請確認相機已喚醒，且此電腦可連上相機後重試。",
      connectionCameraNetwork: "Bridge 無法完成相機網路連線。請檢查相機電源、網路、位址與連接埠後重試。",
      connectionCameraDiscovery: "CCAPI 探索未完成，確切原因不明。請確認已啟用 CCAPI，並核對相機位址、連接埠與網路後再重試。",
      connectionUnknownFailure: "連線請求失敗，目前沒有可確認的原因。請檢查 Bridge 與相機設定，再手動重試。",
      connectionScanIdle: "確認 USB 連線後，請按掃描。",
      connectionScanEmpty: "掃描已完成，但未找到相機。請檢查 USB 資料線、相機電源與 USB 模式，關閉其他相機程式後再按重新掃描。",
      connectionScanFailed: "掃描失敗。請依下方說明處理後，再按重新掃描。",
      connectionScanCancelled: "已取消掃描。準備好後可按重新掃描。",
      connectionScanReady: "請選擇偵測到的相機，再按連線。",
      connectionCcapiReady: "確認相機的 CCAPI 設定後，請按連線。連線前不會傳送相機指令。",
      connectionScanAgain: "重新掃描",
      connectionCancelScan: "取消掃描",
      connectionRetry: "重試連線",
      connectionEditToken: "編輯 Bridge token",
      connectionEditAddress: "編輯相機位址",
      connectionEditCredentials: "編輯相機帳密",
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
      latestMedia: "最新媒體",
      latestMediaEmpty: "尚無最近媒體",
      latestMediaLoading: "正在更新最新媒體",
      openLatestMedia: "開啟最新媒體",
      previouslyVisibleMedia: "先前可見素材",
      openPreviousMedia: "開啟先前可見素材",
      latestMediaSearching: "正在尋找新可見素材…",
      latestVideoSearching: "錄影已停止，正在尋找新可見影片…",
      latestVideoNotReady: "尚未找到新可見影片。可重新查找，不會再次送出錄影命令。",
      latestVideoReadFailed: "暫時無法讀取素材清單。可重新查找，不會再次送出錄影命令。",
      latestVideoFound: "已找到新可見影片；不能據此證明它來自這次錄影。",
      recordingStopUnconfirmed: "相機回傳的狀態尚未確認錄影已停止。",
      recordingStartUnconfirmed: "相機回傳的狀態尚未確認錄影已開始。",
      recordingCommandUnconfirmed: "錄影結果不明。請明確停止錄影；重新整理的快取狀態不能證明相機已停止。",
      recordingStatusUnconfirmed: "錄影命令已接受，但狀態尚未確認。請重新整理狀態；確認前僅可停止錄影。",
      stopUnconfirmedRecording: "停止錄影（狀態未確認）",
      latestMediaNotReady: "尚未找到新可見素材。可重新查找，不會再次拍攝。",
      latestMediaReadFailed: "暫時無法讀取相機的素材清單。可重新查找，不會再次拍攝。",
      latestMediaFound: "已找到新可見素材，請開啟預覽確認。",
      latestMediaReviewLimit: "每次只查找相機回傳的最多 8 筆素材；新出現的素材也可能是先前未顯示的舊檔。",
      latestMediaRetry: "重新查找",
      record: "開始錄影",
      stopRecording: "停止錄影",
      ready: "就緒",
      busy: "處理中",
      autofocus: "AF-ON",
      shutterAF: "快門自動對焦",
      shutterAFOn: "拍攝請求會要求自動對焦。",
      shutterAFOff: "拍攝請求不要求自動對焦；相機或鏡頭設定仍可能使其對焦。",
      shutterAFUnavailable: "此連線不支援不要求自動對焦的拍攝。請開啟快門自動對焦或重新連線。",
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
      liveViewSourceMultipart: "Multipart JPEG",
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
      createCaptureDirectory: "建立拍攝目錄",
      createCaptureDirectoryHint: "留空可使用相機預設名稱，或輸入剛好 5 個大寫英文字母、數字或底線。",
      directoryNamePlaceholder: "EOSXX 或留空",
      directoryCreated: "已建立 {name}",
      create: "建立",
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
      previousShutterWarning: "上一連線的錄影、快門或自動對焦可能仍在作用。再次操作前，請先檢查相機並停止仍在進行的操作。",
      confirmCameraChecked: "我已檢查相機",
      retryBulbStop: "重試停止相機操作",
      bulbReleaseConfirmed: "已確認相機操作停止",
      bulbReleaseUnconfirmed: "尚未確認相機操作停止；快門或自動對焦可能仍在作用。請重試停止，或檢查相機。",
      bulbDisconnectWarning: "本機連線已關閉，但尚未確認相機操作停止。重新連線前請先檢查相機。",
      autofocusComplete: "對焦命令已接受；尚未確認實際合焦",
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
      soundrecording: "錄音",
      soundrecordingmodeintmic: "內建麥克風模式",
      soundrecordingmodeextmic: "外接麥克風模式",
      soundrecordingmodeacc: "配件麥克風模式",
      soundrecordinglevel: "錄音音量",
      soundrecordinglevelintmic: "內建麥克風音量",
      soundrecordinglevelextmic: "外接麥克風音量",
      soundrecordinglevelacc: "配件麥克風音量",
      windfilter: "風聲抑制",
      windfilterintmic: "內建麥克風風聲抑制",
      windfilterextmic: "外接麥克風風聲抑制",
      windfilteracc: "配件麥克風風聲抑制",
      attenuator: "衰減器",
      attenuatorintmic: "內建麥克風衰減器",
      attenuatorextmic: "外接麥克風衰減器",
      attenuatoracc: "配件麥克風衰減器",
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
      movieQualityFine: "精細",
      zoomspeed: "電動變焦速度",
      autopoweroff: "自動關閉電源",
      beep: "提示音",
      displayoff: "螢幕自動關閉",
      capturetarget: "拍攝儲存位置",
      capturestorage: "拍攝記錄卡",
      cardselectionstillimage: "相片記錄卡",
      cardselectionmovie: "影片記錄卡",
      directoryselection: "拍攝目錄",
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
      mediaRecentCount: "最新 {count} 個媒體檔案",
      mediaRecentMoreCount: "最新 {count} 個媒體檔案 - 卡內尚有更多",
      mediaEmpty: "相機未回報任何媒體檔案",
      mediaFilteredCount: "顯示 {visible}／{total} 個媒體檔案",
      mediaLoadingCount: "正在載入相機媒體 - 目前顯示 {count} 個",
      mediaFailedCount: "媒體重新整理失敗 - 顯示先前的 {count} 個",
      mediaNotLoadedCount: "尚未載入媒體",
      mediaScope: "媒體範圍",
      mediaRecent: "最近",
      mediaFullCard: "整張卡",
      mediaFilter: "篩選媒體",
      mediaAll: "全部",
      mediaPhotos: "相片",
      mediaVideos: "影片",
      mediaSort: "排序媒體",
      mediaCameraOrder: "相機順序",
      mediaNewest: "最新優先",
      mediaOldest: "最舊優先",
      mediaName: "檔案名稱",
      mediaPages: "媒體頁面",
      previousMediaPage: "上一頁媒體",
      nextMediaPage: "下一頁媒體",
      mediaPageStatus: "第 {start}-{end} 個，共 {total} 個",
      mediaUnknownDate: "日期不明",
      mediaFolderFilter: "相機資料夾篩選",
      mediaFolderClear: "清除資料夾篩選",
      mediaFolderAll: "全部已載入資料夾",
      mediaFolderUnknown: "未知資料夾",
      mediaFolderHelp: "僅使用相機後端已提供的資料夾資訊，不額外查目錄或中繼資料。資料夾名稱不代表已驗證的實體卡槽。",
      mediaFolderActive: "已載入{total}項中有{matched}項符合，{unknown}項資料夾未知；這不是完整資料夾目錄。",
      mediaFolderMissing: "先前選取的資料夾（目前未載入）",
      mediaFolderDuplicate: "不同資料夾{index}",
      mediaRatingFilter: "評分篩選",
      mediaRatingClear: "清除評分篩選",
      mediaRatingAll: "全部評分",
      mediaRatingUnrated: "未評星（已知0星）",
      mediaRatingOne: "1星以上",
      mediaRatingTwo: "2星以上",
      mediaRatingThree: "3星以上",
      mediaRatingFour: "4星以上",
      mediaRatingFive: "5星",
      mediaRatingUnknown: "未知評分",
      mediaRatingHighest: "評分高至低",
      mediaRatingLowest: "評分低至高",
      mediaRatingHelp: "僅使用已載入評分。未知不等於未評星（0星），不額外讀取相機資訊；最近或未完成清單不代表整張記憶卡。",
      mediaRatingActive: "已載入{total}項中有{matched}項符合，{unknown}項評分未知。僅篩選或排序已載入評分。",
      mediaDateRange: "素材日期區間",
      mediaDateFrom: "開始日期",
      mediaDateTo: "結束日期",
      mediaDateApply: "套用",
      mediaDateCancel: "取消",
      mediaDateClear: "清除日期區間",
      mediaDateHelp: "僅篩選已載入素材，不向相機補查日期。部分連線不提供日期；素材日期可能是檔案修改日期，不一定是拍攝日期。",
      mediaDateZone: "顯示時區：{zone}。僅有日期的值保留原本日期。",
      mediaDateActive: "{start} 至 {end}：已載入 {total} 項中符合 {matched} 項。已載入項目有 {unknown} 項日期不明，已排除。",
      mediaDateRequired: "請輸入開始與結束日期。",
      mediaDateInvalid: "請輸入有效的日曆日期。",
      mediaDateReversed: "結束日期不能早於開始日期。",
      mediaNoMatches: "已載入素材沒有符合篩選的項目。請清除日期、評分或資料夾篩選，或變更素材類型。",
      mediaSelect: "選取 {name}",
      mediaSelectFiltered: "選取全部已載入符合項目",
      mediaSelectionClear: "清除選取",
      mediaSelectionSummary: "已選 {selected} 項 · 篩選隱藏 {hidden} 項 · 目前未載入 {notLoaded} 項",
      mediaSelectionHelp: "跨頁選取已載入且符合篩選的項目，不包含記憶卡上尚未載入的檔案。變更篩選或範圍會保留選取；已隱藏與目前未載入的選取項目也包含在批次中。",
      mediaHistoryTitle: "批次儲存紀錄",
      mediaHistoryOpen: "批次儲存紀錄",
      mediaHistoryHelp: "此瀏覽器設定檔及 Bridge 網址的工作清單與其他已開始嘗試共用 100 列。排隊列尚未確認開始嘗試；已儲存只代表當時瀏覽器確認 close，不保證檔案現在仍存在。未確認嘗試也可能仍在另一分頁執行。名稱僅供顯示，不能識別相機來源；不會自動連線或重試，不含單檔瀏覽器下載。",
      mediaHistoryWarning: "儲存紀錄無法使用或不完整，不影響實際檔案傳輸結果。重試前請先查看目的地。",
      mediaHistoryLoading: "正在讀取紀錄…",
      mediaHistoryUnavailable: "無法讀取紀錄，未清除既有資料。",
      mediaHistoryEmpty: "尚無工作清單或嘗試紀錄。",
      mediaHistoryCount: "共 {count} 列工作清單與其他嘗試",
      mediaHistoryQueued: "排隊中；尚未確認開始嘗試",
      mediaHistoryWorklistPosition: "工作清單 {created} · 第 {position} / {total} 項",
      mediaHistoryLegacyAttempt: "獨立嘗試；沒有完整工作清單",
      mediaWorklistAdmissionFailed: "無法記錄下一項工作清單嘗試，已在開啟檔案或請求原檔前停止。重試前請查看紀錄。",
      mediaWorklistAdmissionUnknown: "無法確認工作清單已儲存，尚未開啟檔案或請求原檔。開始新批次前請查看紀錄。",
      mediaHistoryCompleted: "傳輸當時已儲存",
      mediaHistoryFailed: "失敗",
      mediaHistoryUnconfirmed: "結果未確認，請查看目的地",
      mediaHistoryLeftover: "可能留有檔案或最終狀態未確認。",
      mediaHistoryClear: "清除紀錄…",
      mediaHistoryClearQuestion: "只清除這些工作清單與紀錄？目的檔案與目前傳輸不變；既有批次晚到的結果不會重新建立已清除的紀錄。",
      mediaHistoryClearConfirm: "清除紀錄",
      mediaHistoryClearCancel: "保留紀錄",
      mediaHistoryClearFailed: "未能確認紀錄已清除；紀錄可能仍在，請重新載入查看。",
      mediaBatchStart: "將所選原檔儲存到資料夾…",
      mediaBatchUnavailable: "批次儲存需要支援資料夾存取的瀏覽器。仍可逐一下載原檔。",
      mediaBatchTitle: "原檔批次儲存",
      mediaBatchChoosing: "請選擇資料夾，系統會在其中建立新的獨立批次資料夾。",
      mediaBatchDestination: "目的地：{name}",
      mediaBatchSummary: "已儲存 {completed} · 失敗 {failed} · 結果不確定 {uncertain} · 未開始 {notStarted} · 等待中 {queued} · 處理中 {running}",
      mediaBatchCancel: "取消後續檔案",
      mediaBatchCancelling: "正在取消。目前的相機讀取或檔案關閉仍可能完成；不會開始後續檔案。",
      mediaBatchNotice: "逐一儲存原檔。「已儲存」表示瀏覽器已確認關閉檔案。取消後批次資料夾可能留有不完整檔案；Bridge 的相機讀取仍可能繼續。",
      mediaBatchLeftovers: "批次資料夾中有 {count} 個檔案可能不完整或結果不確定，使用或刪除前請先檢查。",
      mediaBatchDirectoryMayRemain: "選定的目的地可能留有空的批次資料夾。",
      mediaBatchQueued: "等待中",
      mediaBatchRunning: "正在儲存",
      mediaBatchCompleted: "已儲存",
      mediaBatchFailed: "失敗",
      mediaBatchUncertain: "儲存結果不確定，請檢查目的地",
      mediaBatchNotStarted: "未開始",
      mediaBatchFinished: "批次已結束，請查看各檔案結果。",
      mediaBatchStopped: "批次已停止，已儲存檔案會保留。",
      mediaAutoImportTitle: "前景 JPEG 自動匯入",
      mediaAutoImportStart: "啟用 JPEG 匯入並選擇資料夾…",
      mediaAutoImportStop: "停止 JPEG 匯入",
      mediaAutoImportPurpose: "選擇資料夾，在此分頁保持可見時儲存新增的 JPEG；排除既有照片、RAW 與影片。",
      mediaAutoImportHelp: "限制與檔案確認",
      mediaAutoImportEvidence: "以兩次相同的完整 CCAPI 清單建立既有檔案基準，只處理之後新出現的 JPEG 識別碼；這不代表已確認由哪個裝置或程式拍攝。每個待處理 JPEG 必須取得至少相隔一秒、大小相同且大於零的兩次即時中繼資料。原檔只請求一次，且位元組數必須完全符合後才關閉檔案；瀏覽器確認 close 才標示已儲存。時間戳與拍攝事件不代表檔案已完成。",
      mediaAutoImportLimits: "限支援媒體瀏覽及下載的 CCAPI 連線，錄影或 Bulb 期間不可用。只在前景執行，完整清單約每 5 秒一次。上限：每次清單 1,000 筆、已知識別碼 4,096 筆、待處理 JPEG 20 筆、每檔確認期限 30 秒、最近結果 100 筆。每次讀取之間優先讓手動操作執行。",
      mediaAutoImportNotice: "停止、切到背景、離開頁面、斷線或失去支援都會結束本輪，回到頁面不會自動重啟。已開始的讀取或檔案關閉仍可能完成；瀏覽器取消後，Bridge 相機讀取仍可能繼續。可能留有不完整檔案或空資料夾，重試前請檢查目的地。每次都需重新選擇資料夾，不會自動還原存取權限。",
      mediaAutoImportUnavailable: "自動匯入需要瀏覽器支援資料夾存取與安全 UUID；仍可手動逐一下載原檔。",
      mediaAutoImportSummary: "已儲存 {saved} · 待處理 {pending} · 已知識別碼 {known} · 最近結果 {results}（最多 100）",
      mediaAutoImportOff: "未啟用。請明確啟用以開始新的一輪。",
      mediaAutoImportChoosing: "請選擇資料夾。目前尚未讀取相機清單或建立輸出資料夾。",
      mediaAutoImportBaseline: "正在確認既有檔案基準，不會匯入既有照片。",
      mediaAutoImportWatching: "頁面可見時持續啟用，等待新增且穩定的 JPEG，並讓手動操作優先。",
      mediaAutoImportInventory: "正在讀取相機完整清單…",
      mediaAutoImportMetadata: "正在檢查待處理 JPEG 的即時大小…",
      mediaAutoImportSaving: "正在儲存一個已確認的 JPEG 原檔…",
      mediaAutoImportStopping: "正在停止。進行中的讀取或檔案關閉仍可能完成，不會開始後續檔案。",
      mediaAutoImportStopped: "已停止。重新啟用前請查看已儲存及結果不確定的項目。",
      mediaAutoImportUserStopped: "已依您的操作停止。再次啟用會建立新的基準。",
      mediaAutoImportBackgrounded: "頁面已離開前景。需要重新啟用才能開始新的一輪。",
      mediaAutoImportDisconnected: "本輪所屬相機連線已結束。重新連線後需再次啟用。",
      mediaAutoImportCameraUnavailable: "CCAPI 支援改變、開始錄影或 Bulb 已啟動，本輪已停止。",
      mediaAutoImportBaselineChanged: "兩次基準清單不同，未匯入既有檔案。請待相機穩定後重新啟用。",
      mediaAutoImportLimit: "已達清單、識別碼或待處理檔案上限。檢查目的地並準備好後再重新啟用。",
      mediaAutoImportDisappeared: "待處理 JPEG 已從完整清單消失，其結果未確認。",
      mediaAutoImportMetadataDeadline: "待處理 JPEG 未在 30 秒內取得穩定的即時大小證據，未儲存該檔案。",
      mediaAutoImportInventoryUnavailable: "Bridge 無法確認有界限的完整清單，已停止匯入，不會信任部分清單或舊版回應。",
      mediaAutoImportMetadataUnavailable: "無法確認最新且相符的中繼資料，未請求此候選檔案的原檔。",
      mediaAutoImportUnconfirmed: "儲存結果未確認，請檢查目的地；不可假定相機讀取已取消。",
      mediaAutoImportFailed: "原檔請求或儲存失敗。重試前請檢查可能留下的不完整檔案。",
      mediaAutoImportDestinationUnavailable: "無法準備選定的資料夾，請重新啟用並選擇可寫入的資料夾。",
      mediaAutoImportUnknown: "因未識別的原因（{reason}）停止匯入，重試前請檢查目的地。",
      mediaAutoImportProtocol: "缺少必要的嚴格 Bridge 回應證據，或證據不一致。",
      mediaAutoImportBusy: "手動相機操作優先。",

      manageMedia: "管理 {name}",
      mediaDetails: "媒體詳細資料",
      closeMediaDetails: "關閉媒體詳細資料",
      loadingMediaDetails: "正在從相機讀取中繼資料",
      protection: "檔案保護",
      protect: "保護",
      unprotect: "取消保護",
      rating: "評分",
      ratingOff: "關閉",
      ratingValue: "{value} 星",
      rotation: "顯示旋轉",
      rotationValue: "{value} 度",
      archive: "封存",
      unarchive: "取消封存",
      mediaMetadataUpdated: "已更新 {name}",
      mediaThumbnail: "{name} 的縮圖",
      previewMedia: "預覽 {name}",
      closeMediaPreview: "關閉媒體預覽",
      mediaPreviewPosition: "第 {position} 個，共 {total} 個",
      openMediaDetails: "開啟媒體詳細資料",
      resetMediaZoom: "重設縮放",
      loadingPreview: "正在載入相機預覽",
      previewUnavailable: "無法顯示相機提供的預覽影像。",
      videoPlaybackUnavailable: "無法播放相機中的這部影片。",
      videoPlaybackCodecUnsupported: "此瀏覽器無法解碼相機中的 {container} 影片。R6 Mark III 的 10-bit 或 4:2:2 錄影可能需要相容的剪輯軟體。",
      videoPlaybackStorageUnavailable: "可用空間不足，無法準備這部相機中的 {container} 影片。請釋放儲存空間，或下載原始檔案。",
      videoPlaybackTransportUnavailable: "無法播放相機中的 {container} 影片。請檢查相機連線後再試一次。",
      tryVideoAgain: "重新嘗試播放",
      downloadOriginal: "下載原檔",
      previousMedia: "上一個媒體",
      nextMedia: "下一個媒體",
      download: "下載",
      preparingDownload: "正在準備相機檔案下載",
      downloading: "正在下載 {name}",
      downloadProgress: "已傳輸 {transferred}／{total}（{percent}%）",
      downloadProgressUnknown: "已傳輸 {transferred}",
      cancelDownload: "取消下載",
      cancellingDownload: "正在取消下載",
      downloadCancelled: "已取消下載",
      downloaded: "已下載 {name}",
      uploadMedia: "上傳媒體",
      uploading: "正在上傳 {name}",
      uploadProgress: "已傳輸 {transferred}／{total}（{percent}%）",
      cancelUpload: "取消上傳",
      cancellingUpload: "正在取消上傳",
      uploadCancelled: "已取消上傳",
      uploaded: "已上傳 {name}",
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
    healthAttempt: null,
    healthFailed: false,
    scanAttempt: null,
    scanState: "idle",
    connectionFailure: null,
    cameras: [],
    session: null,
    info: null,
    status: null,
    recordingRecovery: null,
    capabilities: null,
    shutterAutofocus: true,
    fileNamingDrafts: {},
    settingDrafts: {},
    operatorConfirmedFeatures: new Set(),
    lastClockSyncAt: null,
    lastCreatedDirectoryName: null,
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
    shutterDisconnectWarning: false,
    shutterReleaseUnconfirmed: false,
    bulbStartedAt: null,
    bulbTimer: null,
    focusStep: "MEDIUM",
    tapAction: "focus",
    media: [],
    mediaSelection: mediaBatch.createSelection(),
    mediaBatchOwner: null,
    mediaBatch: null,
    mediaAutoImportOwner: null,
    mediaAutoImportReading: null,
    mediaAutoImport: null,
    mediaAutoImportThumbnailsDeferred: false,
    mediaLoaded: false,
    mediaLoadStatus: "NOT_LOADED",
    mediaRefreshPromise: null,
    mediaScope: "recent",
    mediaHasMore: false,
    mediaFilter: "all",
    mediaDateRange: null,
    mediaDateDialogSession: null,
    mediaFolderFilter: { kind: "all" },
    mediaFolderDialogSession: null,
    mediaFolderDraftChoices: [],
    mediaRatingFilter: "all",
    mediaRatingDialogSession: null,
    mediaSort: "newest",
    mediaPage: 0,
    mediaThumbnailUrls: new Map(),
    mediaThumbnailLoads: new Set(),
    mediaThumbnailFailures: new Set(),
    mediaGeneration: 0,
    mediaPreviewUrl: null,
    mediaPreviewTicketUrl: null,
    mediaPreviewController: null,
    mediaPreviewItem: null,
    mediaPreviewGeneration: 0,
    mediaPreviewScale: 1,
    mediaPreviewOffset: { x: 0, y: 0 },
    mediaPreviewDrag: null,
    mediaDetailsItem: null,
    mediaDetailsGeneration: 0,
    mediaDetailsBusy: false,
    mediaDownloadPreparing: false,
    mediaDownloadOwner: null,
    mediaDownload: null,
    mediaUpload: null,
    latestMediaItem: null,
    latestMediaThumbnailUrl: null,
    latestMediaThumbnailLoading: false,
    latestMediaGeneration: 0,
    latestMediaRefreshPromise: null,
    latestMediaController: null,
    latestMediaCandidates: null,
    latestMediaReviewAttempt: null,
    latestMediaReviewKind: "photo",
    latestMediaReviewStatus: "IDLE",
    latestMediaReviewReadFailed: false,
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
    connectionStatus: byId("connection-status"),
    connectionFixButton: byId("connection-fix-button"),
    healthRetryButton: byId("health-retry-button"),
    scanCancelButton: byId("scan-cancel-button"),
    ccapiUrlValidation: byId("ccapi-url-validation"),
    cameraCredentials: byId("camera-credentials"),
    shutterDisconnectWarning: byId("shutter-disconnect-warning"),
    shutterDisconnectConfirm: byId("shutter-disconnect-confirm"),
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
    shutterAFControl: byId("shutter-af-control"),
    shutterAFToggle: byId("shutter-af-toggle"),
    shutterAFDescription: byId("shutter-af-description"),
    latestMediaButton: byId("latest-media-button"),
    latestMediaThumbnail: byId("latest-media-thumbnail"),
    latestMediaLabel: byId("latest-media-label"),
    latestMediaHeading: byId("latest-media-heading"),
    latestMediaReview: byId("latest-media-review"),
    latestMediaReviewStatus: byId("latest-media-review-status"),
    latestMediaRetry: byId("latest-media-retry"),
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
    mediaUploadButton: byId("media-upload-button"),
    mediaUploadInput: byId("media-upload-input"),
    mediaSummary: byId("media-summary"),
    mediaFolderButton: byId("media-folder-button"),
    mediaFolderClear: byId("media-folder-clear"),
    mediaFolderSummary: byId("media-folder-summary"),
    mediaFolderDialog: byId("media-folder-dialog"),
    mediaFolderForm: byId("media-folder-form"),
    mediaFolderSelect: byId("media-folder-filter-select"),
    mediaFolderClose: byId("media-folder-close"),
    mediaFolderCancel: byId("media-folder-cancel"),
    mediaFolderDialogClear: byId("media-folder-dialog-clear"),
    mediaRatingButton: byId("media-rating-button"),
    mediaRatingClear: byId("media-rating-clear"),
    mediaRatingSummary: byId("media-rating-summary"),
    mediaRatingDialog: byId("media-rating-dialog"),
    mediaRatingForm: byId("media-rating-form"),
    mediaRatingSelect: byId("media-rating-filter-select"),
    mediaRatingClose: byId("media-rating-close"),
    mediaRatingCancel: byId("media-rating-cancel"),
    mediaRatingDialogClear: byId("media-rating-dialog-clear"),
    mediaDateButton: byId("media-date-button"),
    mediaDateClear: byId("media-date-clear"),
    mediaDateSummary: byId("media-date-summary"),
    mediaDateDialog: byId("media-date-dialog"),
    mediaDateForm: byId("media-date-form"),
    mediaDateFrom: byId("media-date-from"),
    mediaDateTo: byId("media-date-to"),
    mediaDateError: byId("media-date-error"),
    mediaDateZone: byId("media-date-zone"),
    mediaDateClose: byId("media-date-close"),
    mediaDateCancel: byId("media-date-cancel"),
    mediaDateDialogClear: byId("media-date-dialog-clear"),
    mediaList: byId("media-list"),
    mediaScopeControl: byId("media-scope-control"),
    mediaFilterControl: byId("media-filter-control"),
    mediaSortSelect: byId("media-sort-select"),
    mediaPagination: byId("media-pagination"),
    mediaPagePrevious: byId("media-page-previous"),
    mediaPageNext: byId("media-page-next"),
    mediaPageStatus: byId("media-page-status"),
    mediaTransfer: byId("media-transfer"),
    mediaTransferName: byId("media-transfer-name"),
    mediaTransferStatus: byId("media-transfer-status"),
    mediaTransferProgress: byId("media-transfer-progress"),
    mediaTransferCancel: byId("media-transfer-cancel"),
    mediaSelection: byId("media-selection"),
    mediaSelectFiltered: byId("media-select-filtered"),
    mediaSelectionClear: byId("media-selection-clear"),
    mediaSelectionSummary: byId("media-selection-summary"),
    mediaBatchStart: byId("media-batch-start"),
    mediaBatchUnavailable: byId("media-batch-unavailable"),
    mediaBatchPanel: byId("media-batch-panel"),
    mediaBatchDestination: byId("media-batch-destination"),
    mediaBatchSummary: byId("media-batch-summary"),
    mediaBatchList: byId("media-batch-list"),
    mediaBatchCancel: byId("media-batch-cancel"),
    mediaBatchNotice: byId("media-batch-notice"),
    mediaAutoImportPanel: byId("media-auto-panel"),
    mediaAutoImportStart: byId("media-auto-start"),
    mediaAutoImportStop: byId("media-auto-stop"),
    mediaAutoImportStatus: byId("media-auto-status"),
    mediaAutoImportDestination: byId("media-auto-import-destination"),
    mediaAutoImportSummary: byId("media-auto-import-summary"),
    mediaAutoImportReason: byId("media-auto-import-reason"),
    mediaAutoImportList: byId("media-auto-list"),
    mediaAutoImportUnavailable: byId("media-auto-import-unavailable"),
    mediaHistoryDialog: byId("media-history-dialog"),
    mediaHistoryList: byId("media-history-list"),
    mediaHistoryStatus: byId("media-history-status"),
    mediaHistoryClear: byId("media-history-clear"),
    mediaHistoryConfirm: byId("media-history-confirm"),
    mediaHistoryConfirmYes: byId("media-history-confirm-yes"),
    mediaHistoryConfirmNo: byId("media-history-confirm-no"),
    mediaHistoryClose: byId("media-history-close"),
    mediaPreviewDialog: byId("media-preview-dialog"),
    mediaPreviewClose: byId("media-preview-close"),
    mediaPreviewTitle: byId("media-preview-title"),
    mediaPreviewMeta: byId("media-preview-meta"),
    mediaPreviewKind: byId("media-preview-kind"),
    mediaPreviewStage: byId("media-preview-stage"),
    mediaPreviewImage: byId("media-preview-image"),
    mediaPreviewVideo: byId("media-preview-video"),
    mediaPreviewPrevious: byId("media-preview-previous"),
    mediaPreviewNext: byId("media-preview-next"),
    mediaPreviewLoading: byId("media-preview-loading"),
    mediaPreviewUnavailable: byId("media-preview-unavailable"),
    mediaPreviewUnavailableCopy: byId("media-preview-unavailable-copy"),
    mediaPreviewRetry: byId("media-preview-retry"),
    mediaPreviewFailureDownload: byId("media-preview-failure-download"),
    mediaPreviewDownload: byId("media-preview-download"),
    mediaPreviewDetails: byId("media-preview-details"),
    mediaPreviewResetZoom: byId("media-preview-reset-zoom"),
    mediaDetailsDialog: byId("media-details-dialog"),
    mediaDetailsClose: byId("media-details-close"),
    mediaDetailsName: byId("media-details-name"),
    mediaDetailsKind: byId("media-details-kind"),
    mediaDetailsSummary: byId("media-details-summary"),
    mediaDetailsLoading: byId("media-details-loading"),
    mediaProtectionSection: byId("media-protection-section"),
    mediaProtectButton: byId("media-protect-button"),
    mediaUnprotectButton: byId("media-unprotect-button"),
    mediaRatingSection: byId("media-rating-section"),
    mediaRatingControl: byId("media-rating-control"),
    mediaRotationSection: byId("media-rotation-section"),
    mediaRotationControl: byId("media-rotation-control"),
    mediaArchiveSection: byId("media-archive-section"),
    mediaArchiveButton: byId("media-archive-button"),
    mediaUnarchiveButton: byId("media-unarchive-button"),
    mediaDetailsDownload: byId("media-details-download"),
    mediaDetailsDelete: byId("media-details-delete"),
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
    constructor(message, { code = "HTTP_ERROR", status = 0, feature = null, engine = null, connectionReason = null } = {}) {
      super(message);
      this.name = "ApiError";
      this.code = code;
      this.status = status;
      this.feature = feature;
      this.engine = engine;
      this.connectionReason = connectionReason;
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
    renderMediaPreviewNavigation();
    renderMediaDetails();
    renderMediaTransfer();
    renderLatestMedia();
    renderDiagnostics();
    if (ui.mediaHistoryDialog?.open) void refreshMediaHistory().catch(() => {});
    if (mediaHistoryWarning) document.querySelectorAll("[data-media-history-warning]").forEach((node) => { node.textContent = t("mediaHistoryWarning"); });
  }

  async function api(
    path,
    { method = "GET", json, responseType = "json", keepalive = false, signal = null, redirect = "follow" } = {},
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
        redirect,
      });
    } catch (error) {
      if (mediaTransfer.isAbortError(error)) throw error;
      throw new ApiError(error instanceof Error ? error.message : t("bridgeError"), { code: "NETWORK_ERROR" });
    }
    if (!response.ok) {
      let detail = null;
      try {
        detail = await response.json();
      } catch (error) {
        if (mediaTransfer.isAbortError(error)) throw error;
        // A Bridge 502 can carry the result of a camera mutation. If its error
        // envelope is unreadable, the HTTP header alone cannot prove rejection.
        if (response.status === 502) {
          throw new ApiError(`${response.status} ${response.statusText}`, {
            code: "RESPONSE_BODY_UNAVAILABLE", status: response.status,
          });
        }
        // Other explicit HTTP rejections retain their stable fallback.
      }
      const error = detail?.error || {};
      if (response.status === 502 && (typeof error.code !== "string" || !error.code.trim()
        || typeof error.message !== "string" || !error.message.trim())) {
        throw new ApiError(`${response.status} ${response.statusText}`, {
          code: "RESPONSE_BODY_UNAVAILABLE", status: response.status,
        });
      }
      throw new ApiError(error.message || `${response.status} ${response.statusText}`, {
        code: error.code || "HTTP_ERROR",
        status: response.status,
        feature: error.feature,
        engine: error.engine,
        connectionReason: error.connectionReason,
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
    if (mediaTransfer.isAbortError(error)) return;
    const normalized = captureError(error);
    if (normalized.code === "SHUTTER_RELEASE_UNCONFIRMED") {
      state.shutterDisconnectWarning = true;
      state.connectionFailure = ["previousShutterWarning", null];
    } else {
      state.connectionFailure = connectionGuidance.failureGuidance(normalized, state.connectionMode);
    }
    renderConnectionGuidance();
  }

  function clearConnectionError() {
    state.connectionFailure = null;
    ui.connectionError.textContent = "";
    ui.connectionError.hidden = true;
    ui.connectionFixButton.hidden = true;
  }

  function renderConnectionGuidance() {
    const network = state.connectionMode === "ccapi";
    const invalid = network && Boolean(ui.ccapiUrlInput.value.trim()) && !validCcapiUrl();
    ui.ccapiUrlInput.setAttribute("aria-invalid", String(invalid));
    ui.ccapiUrlValidation.hidden = !invalid;
    ui.ccapiUrlValidation.textContent = invalid ? t("connectionInvalidOrigin") : "";
    ui.healthRetryButton.disabled = state.busy || Boolean(state.healthAttempt) || Boolean(state.scanAttempt);
    ui.scanCancelButton.hidden = !state.scanAttempt;
    ui.scanButton.querySelector("span[data-i18n]").textContent = t(state.scanState === "idle" ? "scan" : "connectionScanAgain");
    ui.connectButton.querySelector("span[data-i18n]").textContent = t(state.connectionFailure ? "connectionRetry" : "connect");
    let statusKey;
    if (state.healthAttempt) statusKey = "checkingBridge";
    else if (state.healthFailed) statusKey = "connectionBridgeCheckFailed";
    else if (state.health && (network ? !state.health.engines?.ccapi?.available : !connectionGuidance.availableUsbEngine(state.health))) {
      statusKey = network ? "connectionCcapiEngine" : "connectionUsbEngine";
    } else if (network) statusKey = ui.ccapiUrlInput.value.trim() ? "connectionCcapiReady" : "connectionAddressRequired";
    else statusKey = ({ idle: "connectionScanIdle", scanning: "scanning", empty: "connectionScanEmpty",
      failed: "connectionScanFailed", cancelled: "connectionScanCancelled", ready: "connectionScanReady" })[state.scanState];
    ui.connectionStatus.textContent = t(statusKey || "connectionScanIdle");
    if (state.connectionFailure) {
      const [key, action] = state.connectionFailure;
      ui.connectionError.textContent = t(key);
      ui.connectionError.hidden = false;
      ui.connectionFixButton.hidden = !action;
      ui.connectionFixButton.textContent = t(({ token: "connectionEditToken", address: "connectionEditAddress",
        credentials: "connectionEditCredentials", bridge: "connectionCheckBridge" })[action] || "connectionRetry");
    }
  }

  function correctConnection() {
    const action = state.connectionFailure?.[1];
    if (state.busy || state.scanAttempt) return;
    if (action === "bridge") { void refreshHealth(); return; }
    if (action === "credentials") ui.cameraCredentials.open = true;
    const input = ({ token: ui.tokenInput, address: ui.ccapiUrlInput, credentials: ui.ccapiUsernameInput })[action];
    input?.focus();
  }

  function showToast(message, error = false) {
    clearTimeout(state.toastTimer);
    if (bulbControlLocked() || state.shutterDisconnectWarning) {
      // Persistent shutter alerts already explain recovery; never cover the Stop control.
      ui.toast.hidden = true;
      return;
    }
    ui.toast.textContent = message;
    ui.toast.classList.toggle("error", error);
    ui.toast.hidden = false;
    state.toastTimer = window.setTimeout(() => {
      ui.toast.hidden = true;
    }, error ? 6000 : 3200);
  }

  function renderHealth() {
    if (!state.health) {
      ui.engineState.textContent = t(state.healthFailed ? "bridgeError" : "checkingBridge");
      ui.healthDot.className = "status-dot warning";
      renderConnectionGuidance();
      return;
    }
    const engineName = state.connectionMode === "ccapi" ? "ccapi" : connectionGuidance.availableUsbEngine(state.health);
    const engine = state.health.engines?.[engineName] || null;
    if (engine?.available) {
      const display = engine.version || engineName;
      ui.engineState.textContent = t("bridgeReady", { engine: display });
      ui.engineState.title = engine.detail || "";
      ui.healthDot.className = "status-dot success";
    } else {
      ui.engineState.textContent = t(state.connectionMode === "ccapi" ? "connectionCcapiEngine" : "connectionUsbEngine");
      ui.engineState.title = "";
      ui.healthDot.className = "status-dot warning";
    }
  }

  function validCcapiUrl() {
    return connectionGuidance.validCameraOrigin(ui.ccapiUrlInput.value);
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
    renderConnectionGuidance();
    renderAvailability();
  }

  function selectConnectionMode(mode) {
    if (!["usb", "ccapi"].includes(mode) || state.busy) return;
    cancelCameraScan();
    state.connectionMode = mode;
    clearConnectionError();
    renderConnectionMode();
    renderHealth();
  }

  function renderCameras() {
    const selected = ui.cameraSelect.value;
    ui.cameraSelect.replaceChildren();
    const placeholder = document.createElement("option");
    placeholder.value = "";
    if (!state.cameras.length) {
      placeholder.textContent = t(state.scanState === "empty" ? "noCameras" : "scanFirst");
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
    if (state.healthAttempt || state.busy || state.scanAttempt) return;
    const attempt = {};
    state.healthAttempt = attempt;
    renderConnectionGuidance();
    try {
      const health = await api("/health");
      if (state.healthAttempt !== attempt) return;
      state.health = health;
      state.healthFailed = false;
      if (state.connectionFailure?.[1] === "bridge") clearConnectionError();
      ui.tokenInput.placeholder = t(health.authRequired ? "authRequired" : "tokenOptional");
    } catch (error) {
      if (state.healthAttempt !== attempt || mediaTransfer.isAbortError(error)) return;
      state.health = null;
      state.healthFailed = true;
      showConnectionError(error);
    } finally {
      if (state.healthAttempt === attempt) {
        state.healthAttempt = null;
        renderHealth();
        renderConnectionGuidance();
        renderAvailability();
      }
    }
  }

  function cancelCameraScan() {
    const attempt = state.scanAttempt;
    if (!attempt) return;
    state.scanAttempt = null;
    attempt.controller.abort();
    state.scanState = "cancelled";
    renderHealth();
    renderConnectionGuidance();
    renderAvailability();
  }

  async function scanCameras() {
    if (state.connectionMode !== "usb" || state.busy || state.scanAttempt || state.healthAttempt || state.session) return;
    clearConnectionError();
    state.token = ui.tokenInput.value.trim();
    const attempt = { controller: new AbortController(), session: state.session };
    state.scanAttempt = attempt;
    state.scanState = "scanning";
    state.cameras = [];
    renderCameras();
    renderConnectionGuidance();
    ui.engineState.textContent = t("scanning");
    const current = () => state.scanAttempt === attempt && state.connectionMode === "usb" && state.session === attempt.session;
    try {
      const response = await api("/v1/cameras", { signal: attempt.controller.signal });
      if (!current()) return;
      state.cameras = Array.isArray(response.cameras) ? response.cameras : [];
      state.scanState = state.cameras.length ? "ready" : "empty";
      renderCameras();
      ui.engineState.textContent = state.cameras.length
        ? t("camerasFound", { count: state.cameras.length })
        : t("noCameras");
    } catch (error) {
      if (!current()) return;
      state.scanState = mediaTransfer.isAbortError(error) ? "cancelled" : "failed";
      state.cameras = [];
      renderCameras();
      if (!mediaTransfer.isAbortError(error)) showConnectionError(error);
      renderHealth();
    } finally {
      if (state.scanAttempt === attempt) {
        state.scanAttempt = null;
        if (state.session === attempt.session) {
          renderConnectionGuidance();
          renderAvailability();
        }
      }
    }
  }

  async function connectCamera() {
    if (state.busy || state.scanAttempt || state.healthAttempt || state.session || state.healthFailed) return;
    const network = state.connectionMode === "ccapi";
    const cameraId = ui.cameraSelect.value;
    const ccapiUrl = ui.ccapiUrlInput.value.trim();
    const engineName = network ? "ccapi" : state.cameras.find((camera) => camera.id === cameraId)?.engine;
    if (state.health?.engines?.[engineName]?.available === false) return;
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
        : {
            engine: state.cameras.find((camera) => camera.id === cameraId)?.engine || "auto",
            cameraId,
          };
      state.session = await api("/v1/session", {
        method: "POST",
        json: sessionPayload,
      });
      state.shutterAutofocus = true;
      const sessionId = encodeURIComponent(state.session.id);
      [state.info, state.status, state.capabilities] = await Promise.all([
        api(`/v1/session/${sessionId}/info`),
        api(`/v1/session/${sessionId}/status`),
        api(`/v1/session/${sessionId}/capabilities`),
      ]);
      state.requestedFps = clampFps(Math.min(15, state.capabilities.liveView?.maxFps || 1));
      state.captureMode = captureModeFromCamera() || (state.status.recording ? "video" : "photo");
      syncLiveMagnificationFromCapabilities();
      state.lastError = null;
      setOperationState(t("ready"));
      if (network) {
        writeCameraPreference(CCAPI_URL_KEY, ccapiUrl);
        writeCameraPreference(CCAPI_USERNAME_KEY, sessionPayload.ccapiUsername);
      }
      ui.tokenInput.value = "";
      ui.ccapiPasswordInput.value = "";
      ui.connectionView.hidden = true;
      ui.controlView.hidden = false;
      renderSession();
      startEventLoop();
      void refreshLatestMedia();
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
    // A status read admitted inside a command can predate its ACK. Existing
    // event refresh already waits for this boundary; diagnostics must too.
    if (!state.session || state.recordingCommandOwner?.session === state.session) return false;
    const session = state.session;
    const recordingRecovery = state.recordingRecovery;
    const refreshOwner = {};
    state.refreshRequestOwner = refreshOwner;
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
        state.session !== session ||
        state.refreshGeneration !== refreshGeneration ||
        state.refreshRequestOwner !== refreshOwner ||
        state.recordingRecovery !== recordingRecovery
      ) return false;
      [state.info, state.status, state.capabilities] = [info, status, capabilities];
      if (state.recordingRecovery === recordingRecovery && recordingRecovery?.acknowledged !== false && typeof status.recording === "boolean") {
        state.recordingRecovery = null;
        if (recordingRecovery?.direction === "stop" && status.recording === false && recordingRecovery.reviewAttempt) {
          state.latestMediaReviewAttempt = recordingRecovery.reviewAttempt;
          state.latestMediaReviewKind = "video";
          void refreshLatestMedia();
        }
      }
      state.requestedFps = clampFps(state.requestedFps);
      syncLiveMagnificationFromCapabilities();
      state.lastError = null;
      renderSession();
      const startNotConfirmed = recordingRecovery?.direction === "start" && status.recording === false;
      if (!quiet || startNotConfirmed) setOperationState(t(recordingRecoveryActive() ? (recordingRecovery?.acknowledged === false ? "recordingCommandUnconfirmed" : "recordingStatusUnconfirmed") : startNotConfirmed ? "recordingStartUnconfirmed" : "ready"), recordingRecoveryActive() || startNotConfirmed);
      return true;
    } catch (error) {
      if (
        state.session !== session ||
        state.refreshGeneration !== refreshGeneration ||
        state.refreshRequestOwner !== refreshOwner ||
        state.recordingRecovery !== recordingRecovery
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
    const session = state.session;
    if (!session) return;
    try {
      const capabilities = await api(`/v1/session/${encodeURIComponent(session.id)}/capabilities`);
      // The connection object owns this response, even if a later connection reuses its ID.
      if (state.session !== session) return;
      state.capabilities = capabilities;
    } catch (_) {
      // Evidence refresh must not turn an already successful camera operation into a failure.
    }
  }

  async function disconnectCamera() {
    if (!state.session) return;
    if (typeof stopMediaAutoImport === "function") stopMediaAutoImport("disconnected");
    const sessionId = state.session.id;
    const hadBulbResponsibility = bulbControlLocked();
    let disconnectError = null;
    beginCameraInteraction();
    cancelMediaDownload({ silent: true });
    renderAvailability();
    stopLiveLoop();
    stopLocalVideo({ announce: false });
    await stopEventLoop(sessionId);
    try {
      await api(`/v1/session/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
    } catch (error) {
      state.shutterDisconnectWarning ||= hadBulbResponsibility ||
        error?.code === "SHUTTER_RELEASE_UNCONFIRMED";
      if (state.session?.id !== sessionId) {
        // An old close can leave a previous-camera warning, never reset the
        // replacement session or publish an old error as its current operation.
        renderAvailability();
        return;
      }
      disconnectError = captureError(error);
    }
    if (state.session?.id !== sessionId) return;
    resetSession();
    if (disconnectError) {
      const message = hadBulbResponsibility && disconnectError.code !== "SHUTTER_RELEASE_UNCONFIRMED"
        ? t("bulbDisconnectWarning") : disconnectError.message;
      ui.connectionError.textContent = message;
      ui.connectionError.hidden = false;
      showToast(message, true);
    } else {
      showToast(t("disconnected"));
    }
  }

  function resetSession() {
    // Retiring a connection is not proof that an admitted REC command stopped.
    // Keep only a previous-camera warning; never transfer its Stop to a new session.
    if (state.session && (state.status?.recording === true ||
        state.recordingRecovery?.session === state.session || state.recordingCommandOwner?.session === state.session)) {
      state.shutterDisconnectWarning = true;
    }
    if (typeof stopMediaAutoImport === "function") stopMediaAutoImport("session-retired");
    state.refreshGeneration += 1;
    stopLiveLoop();
    stopLocalVideo({ announce: false });
    cancelEventLoop();
    cancelMediaDownload({ silent: true });
    cancelMediaUpload({ silent: true });
    clearScheduledMediaTransferRender();
    clearMediaThumbnails();
    cancelLatestMediaRefresh();
    closeMediaPreview();
    closeMediaDetails();
    state.session = null;
    state.info = null;
    state.status = null;
    state.recordingRecovery = null;
    state.recordingCommandOwner = null;
    state.capabilities = null;
    state.shutterAutofocus = true;
    state.fileNamingDrafts = {};
    state.settingDrafts = {};
    state.operatorConfirmedFeatures.clear();
    state.lastClockSyncAt = null;
    state.lastCreatedDirectoryName = null;
    state.media = [];
    state.mediaSelection?.clear();
    state.mediaSelection?.replaceLoaded([]);
    state.mediaBatchOwner = null;
    state.mediaBatch = null;
    state.mediaAutoImportOwner = null;
    state.mediaAutoImport = null;
    state.mediaAutoImportThumbnailsDeferred = false;
    // An in-flight owner's reservation is released only when its operation settles.
    state.mediaFolderFilter = { kind: "all" };
    state.mediaFolderDialogSession = null;
    state.mediaFolderDraftChoices = [];
    if (ui.mediaFolderDialog?.open) ui.mediaFolderDialog.close();
    state.mediaRatingFilter = "all";
    state.mediaRatingDialogSession = null;
    if (ui.mediaRatingDialog?.open) ui.mediaRatingDialog.close();
    state.mediaDateRange = null;
    state.mediaDateDialogSession = null;
    if (ui.mediaDateDialog?.open) ui.mediaDateDialog.close();
    state.mediaHasMore = false;
    state.mediaPage = 0;
    state.mediaLoaded = false;
    state.mediaLoadStatus = "NOT_LOADED";
    state.mediaDownloadPreparing = false;
    state.mediaDownloadOwner = null;
    state.mediaDownload = null;
    state.mediaUpload = null;
    state.latestMediaItem = null;
    releaseObjectUrl(state.latestMediaThumbnailUrl);
    state.latestMediaThumbnailUrl = null;
    state.latestMediaThumbnailLoading = false;
    state.latestMediaCandidates = null;
    state.latestMediaReviewAttempt = null;
    state.latestMediaReviewStatus = "IDLE";
    state.latestMediaReviewKind = "photo";
    state.latestMediaReviewReadFailed = false;
    state.captureMode = "photo";
    state.previewInput = "CAMERA";
    state.liveSource = "AUTO";
    state.activeLiveSource = null;
    state.liveMagnification = 1;
    clearBulbTimer();
    state.shutterReleaseUnconfirmed = false;
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
              await refreshMedia({ preservePreview: true });
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
      if (state.session?.id === sessionId) captureError(error);
    }
  }

  function mediaTransferActive() {
    return state.mediaDownloadPreparing || Boolean(state.mediaDownload) || Boolean(state.mediaUpload) ||
      Boolean(state.mediaBatchOwner);
  }

  function cameraInteractionBusy() {
    return state.busy || mediaTransferActive() || Boolean(state.mediaAutoImportReading);
  }

  function beginCameraInteraction() {
    if (ui.focusReticle) ui.focusReticle.hidden = true;
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
    if (key === "movieformat") return movieFormatDisplayValue(rawValue) || rawValue;
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

  function textSettingValueValid(setting, value) {
    if (setting.inputKind !== "text") return false;
    const maximum = Number(setting.maxLength || 255);
    return Number.isInteger(maximum) && maximum >= 1 && maximum <= 255
      && /^[\x20-\x7E]*$/.test(value)
      && new TextEncoder().encode(value).length <= maximum;
  }

  function movieQualityDisplayValue(rawValue) {
    const parts = String(rawValue).split("_");
    if (parts.length < 4 || parts.length > 5 || parts.some((part) => !part)) return null;
    const size = { "4k": "4K", fhd: "FHD", hd: "HD" }[parts[0].toLowerCase()] || parts[0];
    if (!/^\d{4,5}$/.test(parts[1])) return null;
    const frameRateHundredths = Number(parts[1]);
    const frameRate = `${Math.floor(frameRateHundredths / 100)}.${String(frameRateHundredths % 100).padStart(2, "0")}p`;
    const compression = { raw: "RAW", alli: "ALL-I", ipb: "IPB", longgop: "Long GOP" }[parts[2].toLowerCase()];
    if (!frameRate || !compression) return null;
    const labels = [size, frameRate, compression];
    if (parts[3].toLowerCase() === "light") labels.push(t("movieQualityLight"));
    else if (parts[3].toLowerCase() !== "standard") return null;
    if (parts.length === 5) {
      if (parts[4].toLowerCase() === "crop") labels.push(t("movieQualityCrop"));
      else if (parts[4].toLowerCase() === "fine") labels.push(t("movieQualityFine"));
      else return null;
    }
    return labels.join(" / ");
  }

  function movieFormatDisplayValue(rawValue) {
    const normalized = String(rawValue).toLowerCase().replace(/[^a-z0-9]/g, "");
    if (normalized === "raw") return "RAW";
    if (normalized === "mp4") return "MP4";
    return {
      xfhevcsycc42210bit: "XF-HEVC S / 4:2:2 / 10-bit",
      xfhevcsycc42010bit: "XF-HEVC S / 4:2:0 / 10-bit",
      xfavcsycc42210bit: "XF-AVC S / 4:2:2 / 10-bit",
      xfavcsycc4208bit: "XF-AVC S / 4:2:0 / 8-bit",
    }[normalized] || null;
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

  function shutterReleaseUnconfirmed() {
    return state.shutterReleaseUnconfirmed || state.status?.shutterReleaseUnconfirmed === true;
  }

  function bulbControlLocked() {
    return shutterReleaseUnconfirmed() || state.status?.bulbExposureActive === true;
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
    const unconfirmed = shutterReleaseUnconfirmed();
    const active = state.status?.bulbExposureActive === true;
    ui.bulbIndicator.hidden = !active && !unconfirmed;
    if (unconfirmed) {
      ui.bulbIndicator.textContent = t("bulbReleaseUnconfirmed");
    } else if (active) {
      const startedAt = state.bulbStartedAt || Date.now();
      ui.bulbIndicator.textContent = `BULB ${formatBulbElapsed(Date.now() - startedAt)}`;
    }
  }

  function syncBulbTimer() {
    const active = state.status?.bulbExposureActive === true && !shutterReleaseUnconfirmed();
    if (!active) {
      clearBulbTimer();
      renderBulbIndicator();
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
    const focusedControl = document.activeElement;
    if (focusedControl && ui.advancedSettings.contains(focusedControl)) {
      const fileNamingField = focusedControl.dataset?.fileNamingField;
      if (fileNamingField) state.fileNamingDrafts[fileNamingField] = focusedControl.value;
      const settingKey = focusedControl.dataset?.settingKey;
      if (settingKey && focusedControl.matches('input[type="text"]')) {
        state.settingDrafts[settingKey] = focusedControl.value;
      }
    }
    ui.advancedSettings.replaceChildren();
    const settings = (state.capabilities?.settings || []).filter(
      (setting) => !CORE_SETTINGS.includes(setting.key) && settingMatchesCaptureMode(setting),
    );
    const clockSupported = featureSupported(FEATURES.CAMERA_CLOCK_SYNC);
    const directorySupported = featureSupported(FEATURES.DIRECTORY_CONTROL);
    const fileNamingSupported = featureSupported(FEATURES.FILE_NAMING_CONTROL) && Boolean(state.capabilities?.fileNaming);
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
    if (directorySupported) {
      const row = document.createElement("div");
      row.className = "settings-command";
      const copy = document.createElement("span");
      const title = document.createElement("strong");
      title.textContent = t("createCaptureDirectory");
      const detail = document.createElement("small");
      detail.textContent = state.lastCreatedDirectoryName
        ? t("directoryCreated", { name: state.lastCreatedDirectoryName })
        : t("createCaptureDirectoryHint");
      copy.append(title, detail);
      const actions = document.createElement("div");
      actions.className = "settings-command-actions";
      const input = document.createElement("input");
      input.type = "text";
      input.maxLength = 5;
      input.pattern = "[A-Z0-9_]{5}";
      input.placeholder = t("directoryNamePlaceholder");
      input.setAttribute("aria-label", t("createCaptureDirectory"));
      input.addEventListener("input", () => {
        input.value = input.value.toUpperCase().replace(/[^A-Z0-9_]/g, "").slice(0, 5);
        button.disabled = cameraInteractionBusy() || (input.value.length > 0 && input.value.length !== 5);
      });
      const button = document.createElement("button");
      button.type = "button";
      button.className = "button secondary";
      button.dataset.cameraCommand = "directory";
      button.textContent = t("create");
      button.disabled = cameraInteractionBusy();
      button.addEventListener("click", () => createCaptureDirectory(button, input.value));
      actions.append(input, button);
      row.append(copy, actions);
      ui.advancedSettings.append(row);
    }
    if (fileNamingSupported) renderFileNamingSettings();
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
      cleanButton.disabled = cameraInteractionBusy() || Boolean(state.status?.recording) || recordingRecoveryActive() || bulbControlLocked();
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
      button.disabled = cameraInteractionBusy() || Boolean(state.status?.recording) || recordingRecoveryActive() || bulbControlLocked();
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
    if (!settings.length && !clockSupported && !directorySupported && !fileNamingSupported && !sensorCleaningSupported && !sleepSupported) {
      const empty = document.createElement("p");
      empty.className = "supporting";
      empty.textContent = t("notAvailable");
      ui.advancedSettings.append(empty);
      return;
    }
    settings.forEach((setting) => {
      if (setting.inputKind === "text") {
        const row = document.createElement("div");
        row.className = "settings-command text-setting";
        const copy = document.createElement("span");
        const text = document.createElement("strong");
        text.textContent = settingLabel(setting);
        const detail = document.createElement("small");
        detail.textContent = t("textMetadataRule");
        copy.append(text, detail);
        const actions = document.createElement("div");
        actions.className = "settings-command-actions";
        const input = document.createElement("input");
        input.type = "text";
        input.className = "text-metadata-input";
        input.maxLength = Number(setting.maxLength || 255);
        input.value = state.settingDrafts[setting.key] ?? setting.value;
        input.dataset.settingKey = setting.key;
        input.setAttribute("aria-label", settingLabel(setting));
        const button = document.createElement("button");
        button.type = "button";
        button.className = "button secondary";
        button.dataset.cameraCommand = "setting";
        button.dataset.settingKey = setting.key;
        button.textContent = t("apply");
        const refresh = () => {
          state.settingDrafts[setting.key] = input.value;
          input.disabled = cameraInteractionBusy();
          button.disabled = cameraInteractionBusy()
            || !textSettingValueValid(setting, input.value)
            || input.value === setting.value;
        };
        input.addEventListener("input", refresh);
        button.addEventListener("click", () => updateSetting(setting, input.value, button));
        refresh();
        actions.append(input, button);
        row.append(copy, actions);
        ui.advancedSettings.append(row);
        return;
      }
      const label = document.createElement("label");
      const text = document.createElement("span");
      text.textContent = settingLabel(setting);
      if ([
        "zoom",
        "soundrecordinglevel",
        "soundrecordinglevelintmic",
        "soundrecordinglevelextmic",
        "soundrecordinglevelacc",
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

  function renderFileNamingSettings() {
    const naming = state.capabilities.fileNaming;
    const heading = document.createElement("div");
    heading.className = "settings-command";
    const copy = document.createElement("span");
    const title = document.createElement("strong");
    title.textContent = t("fileNaming");
    const detail = document.createElement("small");
    detail.textContent = state.captureMode === "photo"
      ? t("fileNamingPhotoSummary", { mode: fileNamingModeLabel(naming.stillFilenameMode) })
      : t("fileNamingVideoSummary", {
          index: naming.movieIndex,
          reel: naming.movieReelNumber,
          clip: naming.movieClipNumber,
        });
    copy.append(title, detail);
    heading.append(copy);
    ui.advancedSettings.append(heading);

    if (state.captureMode === "photo") {
      const modeRow = fileNamingRow(t("fileNaming"), t("fileNamingRule"));
      const select = document.createElement("select");
      select.setAttribute("aria-label", t("fileNaming"));
      naming.stillFilenameModeOptions.forEach((option) => {
        const element = document.createElement("option");
        element.value = option;
        element.textContent = fileNamingModeLabel(option);
        element.selected = option === naming.stillFilenameMode;
        select.append(element);
      });
      select.disabled = cameraInteractionBusy();
      select.addEventListener("change", () => updateFileNaming("still-filename-mode", select.value, select));
      modeRow.actions.append(select);
      ui.advancedSettings.append(modeRow.row);
      appendFileNamingInput("still-user-setting-1", "stillUserSetting1", naming.stillUserSetting1, 4, true);
      appendFileNamingInput("still-user-setting-2", "stillUserSetting2", naming.stillUserSetting2, 3, true);
    } else {
      appendFileNamingInput("movie-index", "movieIndex", naming.movieIndex, 2, true);
      appendFileNamingInput("movie-reel-number", "movieReelNumber", String(naming.movieReelNumber), 4, false, true);
      appendFileNamingInput("movie-clip-number", "movieClipNumber", String(naming.movieClipNumber), 3, false, true);
      appendFileNamingInput("movie-user-defined", "movieUserDefined", naming.movieUserDefined, 5, false);
    }
  }

  function fileNamingRow(titleText, detailText) {
    const row = document.createElement("div");
    row.className = "settings-command";
    const copy = document.createElement("span");
    const title = document.createElement("strong");
    title.textContent = titleText;
    const detail = document.createElement("small");
    detail.textContent = detailText;
    copy.append(title, detail);
    const actions = document.createElement("div");
    actions.className = "settings-command-actions";
    row.append(copy, actions);
    return { row, actions };
  }

  function appendFileNamingInput(field, labelKey, current, maximumLength, allowUnderscore, numeric = false) {
    const control = fileNamingRow(t(labelKey), t("fileNamingRule"));
    const input = document.createElement("input");
    input.type = numeric ? "number" : "text";
    input.value = state.fileNamingDrafts[field] ?? current;
    input.maxLength = maximumLength;
    input.dataset.fileNamingField = field;
    input.setAttribute("aria-label", t(labelKey));
    if (numeric) {
      const range = field === "movie-reel-number"
        ? state.capabilities.fileNaming.movieReelRange
        : state.capabilities.fileNaming.movieClipRange;
      input.min = String(range.minimum);
      input.max = String(range.maximum);
      input.step = String(range.step);
    }
    const button = document.createElement("button");
    button.type = "button";
    button.className = "button secondary";
    button.textContent = t("apply");
    button.dataset.cameraCommand = "file-naming";
    const refresh = () => {
      if (!numeric) {
        const allowed = allowUnderscore ? /[^A-Z0-9_]/g : /[^A-Z0-9]/g;
        input.value = input.value.toUpperCase().replace(allowed, "").slice(0, maximumLength);
      }
      state.fileNamingDrafts[field] = input.value;
      button.disabled = cameraInteractionBusy() || !fileNamingValueValid(field, input.value);
    };
    input.addEventListener("input", refresh);
    button.addEventListener("click", () => updateFileNaming(field, input.value, button));
    refresh();
    control.actions.append(input, button);
    ui.advancedSettings.append(control.row);
  }

  function fileNamingValueValid(field, value) {
    const naming = state.capabilities?.fileNaming;
    if (!naming) return false;
    if (field === "still-filename-mode") return naming.stillFilenameModeOptions.includes(value);
    if (field === "still-user-setting-1") return /^[A-Z0-9][A-Z0-9_]{3}$/.test(value);
    if (field === "still-user-setting-2") return /^[A-Z0-9][A-Z0-9_]{2}$/.test(value);
    if (field === "movie-index") return /^[A-Z0-9][A-Z0-9_]$/.test(value);
    if (field === "movie-user-defined") return /^[A-Z0-9]{5}$/.test(value);
    if (!/^[1-9][0-9]*$/.test(value)) return false;
    const range = field === "movie-reel-number" ? naming.movieReelRange : naming.movieClipRange;
    const number = Number(value);
    return number >= range.minimum && number <= range.maximum && (number - range.minimum) % range.step === 0;
  }

  function fileNamingModeLabel(value) {
    if (value === "preset_code") return t("fileNamingPresetCode");
    if (value === "usersetting1") return t("stillUserSetting1");
    if (value === "usersetting2") return t("stillUserSetting2");
    return value;
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

  async function createCaptureDirectory(source, name) {
    if (!state.session || cameraInteractionBusy() || !featureSupported(FEATURES.DIRECTORY_CONTROL)) return;
    if (name && !/^[A-Z0-9_]{5}$/.test(name)) {
      showToast(t("createCaptureDirectoryHint"), true);
      return;
    }
    beginCameraInteraction();
    source.disabled = true;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      const result = await api(`/v1/session/${encodeURIComponent(state.session.id)}/directories`, {
        method: "POST",
        json: { name },
      });
      state.lastCreatedDirectoryName = result.name;
      await refreshCapabilityEvidence();
      setOperationState(t("ready"));
      showToast(t("directoryCreated", { name: result.name }));
    } catch (error) {
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      renderSession();
    }
  }

  async function updateFileNaming(field, value, source) {
    if (
      !state.session ||
      cameraInteractionBusy() ||
      !featureSupported(FEATURES.FILE_NAMING_CONTROL) ||
      !fileNamingValueValid(field, value)
    ) return;
    beginCameraInteraction();
    state.lastError = null;
    source.disabled = true;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      state.capabilities.fileNaming = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/file-naming/${encodeURIComponent(field)}`,
        { method: "PUT", json: { value } },
      );
      delete state.fileNamingDrafts[field];
      await refreshCapabilityEvidence();
      setOperationState(t("ready"));
      showToast(t("fileNamingUpdated"));
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
      state.status?.recording || recordingRecoveryActive() ||
      bulbControlLocked()
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
      state.status?.recording || recordingRecoveryActive() ||
      bulbControlLocked()
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
    const videoOnlyPrefixes = ["windfilter", "attenuator"];
    const photoTokens = ["still", "photo", "drive", "imagequality", "capturetarget", "capturestorage", "directory"];
    if (state.captureMode === "photo") {
      return !videoOnlyPrefixes.some((prefix) => key.startsWith(prefix))
        && !videoTokens.some((token) => key.includes(token));
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
      if (setting.inputKind === "text") delete state.settingDrafts[setting.key];
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
    const stopOnly = recordingRecoveryActive();
    if (recording || stopOnly) state.captureMode = "video";
    else if (cameraMode) state.captureMode = cameraMode;
    const releaseOnly = shutterReleaseUnconfirmed();
    const bulbActive = bulbControlLocked();
    const bulb = bulbActive || (state.captureMode === "photo" && isBulbMode());
    if (!featureSupported(FEATURES.VIDEO_RECORDING) && !recording && !stopOnly && state.captureMode === "video") {
      state.captureMode = "photo";
    }
    ui.photoModeButton.classList.toggle("active", state.captureMode === "photo");
    ui.videoModeButton.classList.toggle("active", state.captureMode === "video");
    ui.photoModeButton.setAttribute("aria-pressed", String(state.captureMode === "photo"));
    ui.videoModeButton.setAttribute("aria-pressed", String(state.captureMode === "video"));
    ui.shutterButton.classList.toggle("video", state.captureMode === "video");
    ui.shutterButton.classList.toggle("recording", state.captureMode === "video" && recording && !stopOnly);
    ui.shutterButton.classList.toggle("bulb", bulb);
    ui.shutterButton.classList.toggle("bulb-active", bulbActive);
    ui.recordIndicator.hidden = !recording || stopOnly;
    const labelKey = releaseOnly ? "retryBulbStop" : stopOnly ? "stopUnconfirmedRecording" : bulb
      ? (bulbActive ? "stopBulb" : "startBulb")
      : state.captureMode === "photo" ? "capture" : recording ? "stopRecording" : "record";
    ui.shutterLabel.textContent = t(labelKey);
    ui.shutterButton.setAttribute("aria-label", t(labelKey));
    replaceButtonIcon(
      ui.shutterButton,
      bulbActive ? "square" : state.captureMode === "video" ? (recording || stopOnly ? "square" : "circle") : "camera",
    );
  }

  async function selectCaptureMode(mode) {
    if (mode === "video" && !featureSupported(FEATURES.VIDEO_RECORDING)) return;
    if ((state.status?.recording || recordingRecoveryActive()) && mode !== "video") return;
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

  function shutterAFSupported() {
    return state.capabilities?.shutterAutofocusSupported === true && featureSupported(FEATURES.STILL_CAPTURE);
  }

  function canChangeShutterAF() {
    return Boolean(state.session) && state.captureMode === "photo" && !isBulbMode() &&
      !cameraInteractionBusy() && !bulbControlLocked() && !state.status?.recording && !recordingRecoveryActive() &&
      (shutterAFSupported() || !state.shutterAutofocus);
  }

  function recordingRecoveryActive() {
    return Boolean(state.recordingRecovery && state.recordingRecovery.session === state.session);
  }

  async function operateShutter() {
    if (!state.session || cameraInteractionBusy()) return;
    const session = state.session;
    const sessionId = session.id;
    const isPhoto = state.captureMode === "photo" && !recordingRecoveryActive();
    const releaseOnly = shutterReleaseUnconfirmed();
    const bulbWasActive = bulbControlLocked();
    const bulb = bulbWasActive || (isPhoto && isBulbMode());
    const recordingWasActive = !isPhoto && (Boolean(state.status?.recording) || recordingRecoveryActive());
    const recordingCommandOwner = !isPhoto && !bulb ? { session } : null;
    const supported = bulbWasActive || recordingWasActive || (bulb
      ? featureSupported(FEATURES.BULB_EXPOSURE)
      : isPhoto ? featureSupported(FEATURES.STILL_CAPTURE)
      : featureSupported(FEATURES.VIDEO_RECORDING));
    if (!supported) return;
    const autofocus = state.shutterAutofocus;
    let reviewAttempt = null;
    if (isPhoto && !bulb && !autofocus && !shutterAFSupported()) {
      setOperationState(t("shutterAFUnavailable"), true);
      return;
    }
    beginCameraInteraction();
    if (recordingCommandOwner) state.recordingCommandOwner = recordingCommandOwner;
    state.lastError = null;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      if (bulb) {
        if (!bulbWasActive) pauseLivePolling();
        const bulbPath = bulbWasActive ? "/bulb/stop" : "/bulb/start";
        const status = await api(
          `/v1/session/${encodeURIComponent(sessionId)}${bulbPath}`,
          { method: "POST" },
        );
        if (state.session !== session) return;
        state.status = status;
        state.shutterReleaseUnconfirmed = status.shutterReleaseUnconfirmed === true;
        const result = releaseOnly ? t("bulbReleaseConfirmed") : bulbWasActive ? t("bulbStopped") : t("bulbStarted");
        if (bulbWasActive && !bulbControlLocked()) {
          if (!releaseOnly) flashCapture();
          resumeLivePolling();
        }
        setOperationState(result);
        showToast(result);
      } else if (isPhoto) {
        reviewAttempt = { sessionId, session, kind: "photo", knownIds: visibleMediaIds() };
        state.latestMediaReviewKind = "photo";
        // A new shutter supersedes an old review before the command waits for ACK.
        cancelLatestMediaRefresh();
        state.latestMediaReviewAttempt = null;
        state.latestMediaReviewStatus = "IDLE";
        state.latestMediaReviewReadFailed = false;
        const captured = await api(`/v1/session/${encodeURIComponent(sessionId)}/capture/still`, {
          method: "POST", json: { af: autofocus },
        });
        if (state.session !== session) return;
        state.status = captured;
        flashCapture();
        setOperationState(t("captureComplete"));
        showToast(t("captureComplete"));
        state.latestMediaReviewAttempt = reviewAttempt;
        void refreshLatestMedia();
      } else {
        const wasRecording = recordingWasActive;
        if (wasRecording) {
          const priorStop = recordingRecoveryActive() && state.recordingRecovery.direction === "stop"
            ? state.recordingRecovery.reviewAttempt : null;
          reviewAttempt = priorStop || { sessionId, session, kind: "video", knownIds: visibleMediaIds() };
          cancelLatestMediaRefresh();
          state.latestMediaReviewAttempt = null;
          state.latestMediaReviewKind = "video";
          state.latestMediaReviewStatus = "IDLE";
          state.latestMediaReviewReadFailed = false;
        }
        const status = await api(
          `/v1/session/${encodeURIComponent(sessionId)}/recording/${wasRecording ? "stop" : "start"}`,
          { method: "POST" },
        );
        if (state.session !== session) return;
        state.status = status;
        const stopped = status.recording === false;
        state.recordingRecovery = typeof status.recording === "boolean" ? null : { session, direction: wasRecording ? "stop" : "start", reviewAttempt };
        const uncertainStop = wasRecording ? !stopped : status.recording !== true;
        const result = wasRecording ? t(stopped ? "recordingStopped" : "recordingStopUnconfirmed") : t(status.recording === true ? "recordingStarted" : "recordingStartUnconfirmed");
        setOperationState(result, uncertainStop);
        showToast(result, uncertainStop);
        if (wasRecording && stopped) {
          state.latestMediaReviewAttempt = reviewAttempt;
          void refreshLatestMedia();
        }
      }
    } catch (error) {
      if (state.session !== session) return;
      const normalized = captureError(error);
      const scopedRecordingUncertainty = normalized.code === "RECORDING_COMMAND_UNCONFIRMED"
        && normalized.status === 502 && normalized.feature === FEATURES.VIDEO_RECORDING
        && normalized.engine === "ccapi";
      if (recordingCommandOwner && (scopedRecordingUncertainty
        || normalized.code === "NETWORK_ERROR"
        || (normalized.code === "RESPONSE_BODY_UNAVAILABLE" && normalized.status === 502)
        || (normalized.code === "HTTP_ERROR" && normalized.status === 0))) {
        state.recordingRecovery = { session, direction: recordingWasActive ? "stop" : "start", reviewAttempt, acknowledged: false };
        state.captureMode = "video";
        setOperationState(t("recordingCommandUnconfirmed"), true);
        showToast(t("recordingCommandUnconfirmed"), true);
        return;
      }
      if (!isPhoto && normalized.code === "RECORDING_STATUS_READBACK_FAILED") {
        state.recordingRecovery = { session, direction: recordingWasActive ? "stop" : "start", reviewAttempt };
        state.captureMode = "video";
        setOperationState(t("recordingStatusUnconfirmed"), true);
        showToast(t("recordingStatusUnconfirmed"), true);
        return;
      }
      if (normalized.code === "CAPTURE_STATUS_READBACK_FAILED" && reviewAttempt?.kind === "photo") {
        state.latestMediaReviewAttempt = reviewAttempt;
        void refreshLatestMedia();
      }
      if (normalized.code === "SHUTTER_RELEASE_UNCONFIRMED") {
        state.shutterReleaseUnconfirmed = true;
        pauseLivePolling();
      }
      if (bulb) {
        // Keep stop responsibility even if the API response or a later read is lost.
        const uncertain = bulbWasActive || [
          "SHUTTER_RELEASE_UNCONFIRMED", "NETWORK_ERROR", "HTTP_ERROR",
        ].includes(normalized.code);
        state.shutterReleaseUnconfirmed ||= uncertain;
        try {
          const status = await api(`/v1/session/${encodeURIComponent(sessionId)}/status`);
          if (state.session !== session) return;
          state.status = status;
          if (
            bulbWasActive && status.shutterReleaseUnconfirmed === false &&
            status.bulbExposureActive === false
          ) {
            // This read follows this Stop in the same session. Ordinary polls cannot clear
            // the sticky warning, and missing fields from older servers are not proof.
            state.shutterReleaseUnconfirmed = false;
            state.lastError = null;
            resumeLivePolling();
            setOperationState(t("bulbReleaseConfirmed"));
            showToast(t("bulbReleaseConfirmed"));
            return;
          }
          state.shutterReleaseUnconfirmed ||= status.shutterReleaseUnconfirmed === true;
        } catch (_) {
          if (state.session !== session) return;
          // A failed refresh never erases the stop-only warning.
          state.shutterReleaseUnconfirmed = true;
        }
        if (shutterReleaseUnconfirmed()) pauseLivePolling();
        else if (!bulbWasActive) resumeLivePolling();
      }
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      if (recordingCommandOwner && state.recordingCommandOwner === recordingCommandOwner) {
        state.recordingCommandOwner = null;
      }
      if (state.session === session) {
        state.busy = false;
        renderSession();
      }
    }
  }

  async function autofocus() {
    if (!state.session || cameraInteractionBusy() || !featureSupported(FEATURES.AUTOFOCUS)) return;
    const session = state.session;
    const sessionId = session.id;
    beginCameraInteraction();
    const generation = state.refreshGeneration;
    const isCurrent = () => state.session === session && state.refreshGeneration === generation;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      const status = await api(`/v1/session/${encodeURIComponent(sessionId)}/focus/auto`, {
        method: "POST",
      });
      if (!isCurrent()) return;
      state.status = status;
      setOperationState(t("autofocusComplete"));
      showToast(t("autofocusComplete"));
    } catch (error) {
      if (!isCurrent()) return;
      const normalized = captureError(error);
      if (normalized.code === "SHUTTER_RELEASE_UNCONFIRMED") {
        state.shutterReleaseUnconfirmed = true;
        pauseLivePolling();
      }
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      if (isCurrent()) {
        state.busy = false;
        renderSession();
      }
    }
  }

  async function halfPressShutter() {
    if (!state.session || cameraInteractionBusy() || !featureSupported(FEATURES.SHUTTER_HALF_PRESS)) return;
    const session = state.session;
    const sessionId = session.id;
    beginCameraInteraction();
    const generation = state.refreshGeneration;
    const isCurrent = () => state.session === session && state.refreshGeneration === generation;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      const status = await api(`/v1/session/${encodeURIComponent(sessionId)}/shutter/half-press`, {
        method: "POST",
      });
      if (!isCurrent()) return;
      state.status = status;
      setOperationState(t("halfPressComplete"));
      showToast(t("halfPressComplete"));
    } catch (error) {
      if (!isCurrent()) return;
      const normalized = captureError(error);
      if (normalized.code === "SHUTTER_RELEASE_UNCONFIRMED") {
        state.shutterReleaseUnconfirmed = true;
        pauseLivePolling();
      }
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      if (isCurrent()) {
        state.busy = false;
        renderSession();
      }
    }
  }

  function flashCapture() {
    ui.captureFlash.classList.remove("active");
    void ui.captureFlash.offsetWidth;
    ui.captureFlash.classList.add("active");
  }

  function cancelLatestMediaRefresh() {
    state.latestMediaGeneration += 1;
    state.latestMediaController?.abort();
    state.latestMediaController = null;
    state.latestMediaRefreshPromise = null;
    state.latestMediaThumbnailLoading = false;
    renderLatestMedia();
  }

  function visibleMediaIds() {
    const ids = new Set(state.media.map((item) => item.id));
    if (state.latestMediaItem) ids.add(state.latestMediaItem.id);
    if (state.latestMediaCandidates?.sessionId === state.session?.id) {
      state.latestMediaCandidates.ids.forEach((id) => ids.add(id));
    }
    return ids;
  }

  function latestMediaFrom(items, knownIds = new Set(), photosOnly = false, videosOnly = false) {
    return mediaLibrary.itemsForDisplay(
      (Array.isArray(items) ? items : []).filter((item) =>
        !knownIds.has(item.id) && (!photosOnly || !mediaIsVideo(item)) && (!videosOnly || mediaIsVideo(item)),
      ),
      "all",
      // The bounded camera response is already ordered by the camera. Keep that
      // order because fresh captures may not have a timestamp yet.
      "camera",
      resolvedLanguage(),
    )[0] || null;
  }

  function retryLatestMedia() {
    const attempt = state.latestMediaReviewAttempt;
    if (
      !attempt || attempt.sessionId !== state.session?.id || cameraInteractionBusy() ||
      state.latestMediaRefreshPromise || state.latestMediaReviewStatus !== "NOT_READY"
    ) return state.latestMediaRefreshPromise || Promise.resolve(false);
    return refreshLatestMedia();
  }

  function refreshLatestMedia() {
    if (!state.session || !featureSupported(FEATURES.MEDIA_BROWSER)) return Promise.resolve(false);
    const reviewAttempt = state.latestMediaReviewAttempt?.sessionId === state.session.id
      ? state.latestMediaReviewAttempt : null;
    state.latestMediaReviewAttempt = reviewAttempt;
    cancelLatestMediaRefresh();
    const rawSessionId = state.session.id;
    const generation = state.latestMediaGeneration;
    const controller = new AbortController();
    state.latestMediaController = controller;
    const stillOwnsReview = () => generation === state.latestMediaGeneration &&
      state.session?.id === rawSessionId && !controller.signal.aborted &&
      state.latestMediaReviewAttempt === reviewAttempt;
    const promise = (async () => {
      state.latestMediaThumbnailLoading = true;
      state.latestMediaReviewStatus = reviewAttempt ? "SEARCHING" : "IDLE";
      state.latestMediaReviewReadFailed = false;
      renderLatestMedia();
      try {
        let latest = null;
        for (let attempt = 0; attempt <= LATEST_MEDIA_RETRY_DELAYS_MILLIS.length; attempt += 1) {
          if (!stillOwnsReview()) return false;
          try {
            const response = await api(
              `/v1/session/${encodeURIComponent(rawSessionId)}/media?limit=${LATEST_MEDIA_LIMIT}`,
              { signal: controller.signal },
            );
            if (!stillOwnsReview()) return false;
            const candidates = (Array.isArray(response.items) ? response.items : []).slice(0, LATEST_MEDIA_LIMIT);
            state.latestMediaCandidates = { sessionId: rawSessionId, ids: new Set(candidates.map((item) => item.id)) };
            state.latestMediaReviewReadFailed = false;
            latest = latestMediaFrom(candidates, reviewAttempt?.knownIds,
              Boolean(reviewAttempt) && reviewAttempt.kind !== "video", reviewAttempt?.kind === "video");
          } catch (error) {
            if (!stillOwnsReview() || mediaTransfer.isAbortError(error)) return false;
            if (!reviewAttempt) throw error;
            state.latestMediaReviewReadFailed = true;
          }
          if (!reviewAttempt || latest) break;
          if (attempt === LATEST_MEDIA_RETRY_DELAYS_MILLIS.length) {
            state.latestMediaThumbnailLoading = false;
            state.latestMediaReviewStatus = "NOT_READY";
            renderLatestMedia();
            return false;
          }
          await sleep(LATEST_MEDIA_RETRY_DELAYS_MILLIS[attempt]);
        }
        if (!stillOwnsReview()) return false;
        if (reviewAttempt) {
          state.latestMediaReviewAttempt = null;
          state.latestMediaReviewStatus = "READY";
        }
        await publishLatestMedia(latest, generation, rawSessionId, controller.signal);
        return true;
      } catch (error) {
        if (!mediaTransfer.isAbortError(error) && stillOwnsReview()) {
          // Latest-media enrichment is best effort and must not rewrite capture success.
          state.latestMediaThumbnailLoading = false;
          state.latestMediaReviewStatus = reviewAttempt ? "NOT_READY" : "IDLE";
          state.latestMediaReviewReadFailed = Boolean(reviewAttempt);
          renderLatestMedia();
        }
        return false;
      } finally {
        if (state.latestMediaController === controller) {
          state.latestMediaController = null;
          state.latestMediaRefreshPromise = null;
          renderLatestMedia();
        }
      }
    })();
    state.latestMediaRefreshPromise = promise;
    return promise;
  }

  async function publishLatestMedia(item, generation, rawSessionId, signal) {
    if (generation !== state.latestMediaGeneration || state.session?.id !== rawSessionId) return;
    const oldUrl = state.latestMediaThumbnailUrl;
    state.latestMediaItem = item;
    state.latestMediaThumbnailUrl = null;
    state.latestMediaThumbnailLoading = Boolean(
      item && !mediaIsVideo(item) && featureSupported(FEATURES.MEDIA_THUMBNAIL),
    );
    if (oldUrl) releaseObjectUrl(oldUrl);
    renderLatestMedia();
    if (!item || mediaIsVideo(item) || !featureSupported(FEATURES.MEDIA_THUMBNAIL)) return;
    await loadLatestMediaThumbnail(item, generation, rawSessionId, signal);
  }

  async function loadLatestMediaThumbnail(item, generation, rawSessionId, signal) {
    const session = state.session;
    try {
      if (session?.id !== rawSessionId || generation !== state.latestMediaGeneration ||
          state.latestMediaItem?.id !== item.id || signal?.aborted) return;
      // Capture review normally owns a busy promise already. If it reaches this
      // stage during an older import read, wait for that reservation, not a timer.
      while (state.mediaAutoImportReading) {
        const reservation = state.mediaAutoImportReading;
        if (!reservation.readSettled) return;
        await reservation.readSettled;
        if (state.session !== session || generation !== state.latestMediaGeneration || signal?.aborted) return;
      }
      if (state.session !== session || generation !== state.latestMediaGeneration ||
          state.latestMediaItem?.id !== item.id || signal?.aborted) return;
      const blob = await api(
        `/v1/session/${encodeURIComponent(rawSessionId)}/media/${encodeURIComponent(item.id)}/thumbnail`,
        { responseType: "blob", signal },
      );
      if (!blob.type.startsWith("image/") || blob.size <= 0 || blob.size > MAX_MEDIA_THUMBNAIL_BYTES) {
        throw new ApiError("Invalid media thumbnail", { code: "INVALID_MEDIA_THUMBNAIL" });
      }
      if (
        generation !== state.latestMediaGeneration ||
        state.session !== session || state.session?.id !== rawSessionId ||
        state.latestMediaItem?.id !== item.id
      ) return;
      state.latestMediaThumbnailUrl = URL.createObjectURL(blob);
    } catch (_) {
      // Keep the media icon when a camera cannot provide a thumbnail.
    } finally {
      if (
        generation === state.latestMediaGeneration &&
        state.session === session && state.session?.id === rawSessionId &&
        state.latestMediaItem?.id === item.id
      ) {
        state.latestMediaThumbnailLoading = false;
        renderLatestMedia();
      }
    }
  }

  function renderLatestMedia() {
    if (typeof renderMediaAutoImport === "function") renderMediaAutoImport();
    const button = ui.latestMediaButton;
    if (!button) return;
    const supported = Boolean(state.session) && featureSupported(FEATURES.MEDIA_BROWSER);
    const reviewStatus = state.latestMediaReviewStatus;
    const reviewPrefix = state.latestMediaReviewKind === "video" ? "latestVideo" : "latestMedia";
    const pending = reviewStatus === "SEARCHING" || reviewStatus === "NOT_READY";
    button.hidden = !supported;
    button.disabled = !state.latestMediaItem;
    button.setAttribute("aria-label", state.latestMediaItem
      ? t(pending ? "openPreviousMedia" : "openLatestMedia") : t("latestMediaEmpty"));
    ui.latestMediaHeading.textContent = t(pending ? "previouslyVisibleMedia" : "latestMedia");
    ui.latestMediaReview.hidden = !supported || reviewStatus === "IDLE";
    ui.latestMediaReviewStatus.textContent = t(reviewStatus === "SEARCHING" ? `${reviewPrefix}Searching` :
      reviewStatus === "NOT_READY" ? (state.latestMediaReviewReadFailed ? `${reviewPrefix}ReadFailed` : `${reviewPrefix}NotReady`) :
        `${reviewPrefix}Found`);
    ui.latestMediaRetry.hidden = !pending;
    ui.latestMediaRetry.disabled = reviewStatus !== "NOT_READY" || cameraInteractionBusy() ||
      Boolean(state.latestMediaRefreshPromise);
    ui.latestMediaLabel.textContent = state.latestMediaThumbnailLoading && !pending
      ? t("latestMediaLoading")
      : state.latestMediaItem?.name || t("latestMediaEmpty");
    ui.latestMediaThumbnail.replaceChildren();
    if (state.latestMediaThumbnailUrl) {
      const image = document.createElement("img");
      image.src = state.latestMediaThumbnailUrl;
      image.alt = state.latestMediaItem?.name || t("latestMedia");
      ui.latestMediaThumbnail.append(image);
    } else {
      const icon = document.createElement("span");
      icon.className = "icon";
      icon.dataset.icon = mediaIsVideo(state.latestMediaItem) ? "video" : "images";
      ui.latestMediaThumbnail.append(icon);
    }
    window.OpenEosIcons?.render(ui.latestMediaThumbnail);
  }

  function openLatestMedia() {
    if (state.latestMediaItem) void openMediaPreview(state.latestMediaItem);
  }

  function liveCapabilities() {
    return state.capabilities?.liveView || {};
  }

  function liveMagnifications() {
    const values = Array.isArray(liveCapabilities().magnifications)
      ? liveCapabilities().magnifications
        .filter((value) => Number.isInteger(value) && [1, 5, 10].includes(value))
      : [];
    return [...new Set(values)];
  }

  function syncLiveMagnificationFromCapabilities() {
    const capabilities = liveCapabilities();
    const values = liveMagnifications();
    const current = capabilities.currentMagnification;
    if (values.includes(current)) state.liveMagnification = current;
    else if (values.length) state.liveMagnification = values[0];
  }

  function nextLiveMagnification() {
    const values = liveMagnifications();
    if (!values.length) return null;
    const index = values.indexOf(state.liveMagnification);
    return values[(index >= 0 ? index + 1 : 0) % values.length];
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
    if (
      !state.session || !localPreviewSelected() || !state.localVideoSupport.available ||
      state.localVideoBusy || shutterReleaseUnconfirmed()
    ) return;
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
      CCAPI_MULTIPART: "liveViewSourceMultipart",
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
      localPreviewSelected() || !state.session || cameraInteractionBusy() || bulbControlLocked() ||
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
      syncLiveMagnificationFromCapabilities();
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
    const session = state.session;
    if (!session || !state.liveActive || state.activeLiveSource !== "CCAPI_RTP" || localPreviewSelected()) {
      renderRtpAudio();
      return;
    }
    const liveGeneration = state.liveGeneration;
    const refreshGeneration = state.refreshGeneration;
    const ownsRefresh = () => state.session === session && state.liveGeneration === liveGeneration &&
      state.refreshGeneration === refreshGeneration && state.liveActive &&
      state.activeLiveSource === "CCAPI_RTP" && !localPreviewSelected();
    try {
      const status = await api(`/v1/session/${encodeURIComponent(session.id)}/status`);
      if (!ownsRefresh()) return;
      state.status = status;
    } catch (_) {
      // Video remains usable even when this optional status refresh fails.
    }
    if (ownsRefresh()) renderRtpAudio();
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
    if (state.rtpAudioBusy) return;
    const session = state.session;
    const status = currentRtpAudioStatus();
    if (!session || !state.liveActive || state.activeLiveSource !== "CCAPI_RTP" ||
      localPreviewSelected() || !status?.available) {
      showToast(status?.reason || t("cameraAudioUnavailable"), true);
      return;
    }
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) {
      showToast(t("cameraAudioUnavailable"), true);
      return;
    }
    const liveGeneration = state.liveGeneration;
    const generation = ++state.rtpAudioLoopGeneration;
    const ownsStart = () => state.rtpAudioLoopGeneration === generation && state.session === session &&
      state.liveGeneration === liveGeneration && state.liveActive &&
      state.activeLiveSource === "CCAPI_RTP" && !localPreviewSelected();
    state.rtpAudioBusy = true;
    state.rtpAudioError = null;
    renderRtpAudio();
    let context = null;
    try {
      context = new AudioContextClass({ latencyHint: "interactive", sampleRate: 48_000 });
      // Stop owns cleanup even while resume is pending (and may never settle).
      state.rtpAudioContext = context;
      await context.resume();
      if (!ownsStart()) return;
      state.rtpAudioEnabled = true;
      state.rtpAudioAfterGeneration = 0;
      state.rtpAudioNextStart = Number.NaN;
      void pollRtpAudio(generation);
      showToast(t("cameraAudioStarted"));
    } catch (error) {
      if (!ownsStart()) return;
      state.rtpAudioError = error instanceof Error ? error.message : String(error);
      showToast(state.rtpAudioError, true);
    } finally {
      // A stopped attempt must not clear a newer start's busy state or context.
      if (state.rtpAudioLoopGeneration === generation) {
        if (!state.rtpAudioEnabled && state.rtpAudioContext === context) {
          state.rtpAudioContext = null;
          if (context) void context.close().catch(() => {});
        }
        state.rtpAudioBusy = false;
        renderRtpAudio();
      }
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
    const bulbActive = bulbControlLocked();
    const values = liveMagnifications();
    const target = nextLiveMagnification();
    const available = values.length >= 2 && state.captureMode !== "video";
    ui.liveMagnificationButton.hidden = !supported || !cameraPreview || !available;
    ui.liveMagnificationButton.disabled = !cameraPreview || cameraInteractionBusy() || bulbActive ||
      !state.liveActive || !supported || !available;
    ui.liveMagnificationLabel.textContent = target === null ? "--" : `${target}x`;
    const description = t("liveViewMagnification", { value: target ?? state.liveMagnification });
    ui.liveMagnificationButton.setAttribute("aria-label", description);
    ui.liveMagnificationButton.title = description;
    replaceButtonIcon(ui.liveMagnificationButton, target > state.liveMagnification ? "zoom-in" : "zoom-out");
  }

  async function setLiveViewMagnification() {
    if (
      localPreviewSelected() || !state.session || cameraInteractionBusy() || !state.liveActive ||
      bulbControlLocked() ||
      state.captureMode === "video" ||
      !featureSupported(FEATURES.LIVE_VIEW_MAGNIFICATION) || liveMagnifications().length < 2
    ) return;
    const target = nextLiveMagnification();
    if (target === null) return;
    beginCameraInteraction();
    setOperationState(t("busy"));
    renderAvailability();
    try {
      const result = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/liveview/magnification`,
        { method: "POST", json: { value: target } },
      );
      if (result.accepted && liveMagnifications().includes(Number(result.value))) {
        state.liveMagnification = Number(result.value);
      }
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
    const session = state.session;
    beginCameraInteraction();
    const generation = state.refreshGeneration;
    const isCurrent = () => state.session === session && state.refreshGeneration === generation;
    ui.focusReticle.style.left = `${point.displayX}px`;
    ui.focusReticle.style.top = `${point.displayY}px`;
    ui.focusReticle.className = "focus-reticle focusing";
    ui.focusReticle.hidden = false;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      if (action === "whiteBalance") {
        const status = await api(`/v1/session/${encodeURIComponent(session.id)}/whitebalance/click`, {
          method: "POST",
          json: { x: point.x, y: point.y },
        });
        if (!isCurrent()) return;
        state.status = status;
      } else {
        await api(`/v1/session/${encodeURIComponent(session.id)}/focus/tap`, {
          method: "POST",
          json: { x: point.x, y: point.y },
        });
      }
      if (!isCurrent()) return;
      ui.focusReticle.className = action === "whiteBalance" ? "focus-reticle success" : "focus-reticle accepted";
      setOperationState(t(action === "whiteBalance" ? "clickWhiteBalanceAccepted" : "focusAccepted"));
      window.setTimeout(() => { if (isCurrent()) ui.focusReticle.hidden = true; }, 900);
    } catch (error) {
      if (!isCurrent()) return;
      const normalized = captureError(error);
      ui.focusReticle.className = "focus-reticle failure";
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
      window.setTimeout(() => { if (isCurrent()) ui.focusReticle.hidden = true; }, 1300);
    } finally {
      if (isCurrent()) {
        state.busy = false;
        renderSession();
      }
    }
  }

  function tapFocusFromPointer(event) {
    if (event.target.closest?.("button")) return;
    tapFocus(focusPointFromClient(event.clientX, event.clientY));
  }

  function renderAvailability() {
    if (state.mediaAutoImportOwner && !state.mediaAutoImportOwner.cancelled && !mediaAutoImportEligible()) {
      stopMediaAutoImport("camera-unavailable");
    }
    ui.shutterDisconnectWarning.hidden = !state.shutterDisconnectWarning;
    const connected = Boolean(state.session);
    const warningHost = connected ? ui.shutterButton.parentElement : ui.connectionError.parentElement;
    if (ui.shutterDisconnectWarning.parentElement !== warningHost) {
      warningHost.append(ui.shutterDisconnectWarning);
    }
    if (bulbControlLocked() || state.shutterDisconnectWarning) ui.toast.hidden = true;
    const bulbActive = bulbControlLocked();
    const interactionBusy = cameraInteractionBusy();
    const videoSupported = featureSupported(FEATURES.VIDEO_RECORDING);
    const recording = Boolean(state.status?.recording);
    const stopOnly = recordingRecoveryActive();
    const videoAvailable = videoSupported || recording || stopOnly;
    [ui.usbModeButton, ui.ccapiModeButton, ui.tokenInput, ui.ccapiUrlInput,
      ui.ccapiUsernameInput, ui.ccapiPasswordInput].forEach((input) => {
      if (input) input.disabled = state.busy;
    });
    ui.scanButton.disabled = state.busy || Boolean(state.scanAttempt) || Boolean(state.healthAttempt);
    const connectionReady = state.connectionMode === "ccapi" ? validCcapiUrl() : Boolean(ui.cameraSelect.value);
    const selectedEngine = state.connectionMode === "ccapi" ? "ccapi"
      : state.cameras.find((camera) => camera.id === ui.cameraSelect.value)?.engine;
    ui.connectButton.disabled = state.busy || Boolean(state.scanAttempt) || Boolean(state.healthAttempt) ||
      state.healthFailed || state.health?.engines?.[selectedEngine]?.available === false || !connectionReady;
    if (ui.healthRetryButton) ui.healthRetryButton.disabled = state.busy || Boolean(state.scanAttempt) || Boolean(state.healthAttempt);
    if (ui.connectionFixButton) ui.connectionFixButton.disabled = state.busy || Boolean(state.scanAttempt);
    ui.refreshButton.disabled = !connected || interactionBusy;
    ui.disconnectButton.disabled = !connected || state.busy;
    ui.photoModeButton.disabled = interactionBusy || bulbActive || recording || stopOnly;
    ui.videoModeButton.disabled = interactionBusy || bulbActive || !videoAvailable;
    ui.videoModeButton.hidden = !videoAvailable;
    ui.videoModeButton.parentElement.classList.toggle("single", !videoAvailable);
    const bulbActiveStop = bulbActive;
    const shutterSupported = bulbActiveStop || recording || stopOnly || (state.captureMode === "photo" && isBulbMode()
      ? featureSupported(FEATURES.BULB_EXPOSURE)
      : state.captureMode === "photo" ? featureSupported(FEATURES.STILL_CAPTURE)
      : videoSupported);
    const temperatureAllowed = bulbActiveStop || recording || stopOnly || (
      state.captureMode === "photo" ? temperatureAllows("release") : temperatureAllows("movie")
    );
    const shutterAFAllowed = bulbActiveStop || state.captureMode !== "photo" || isBulbMode() ||
      state.shutterAutofocus || shutterAFSupported();
    ui.shutterButton.disabled = interactionBusy || !shutterSupported || !temperatureAllowed || !shutterAFAllowed;
    ui.shutterAFControl.hidden = !connected || state.captureMode !== "photo" || isBulbMode() || bulbActive ||
      (!shutterAFSupported() && state.shutterAutofocus);
    ui.shutterAFToggle.checked = state.shutterAutofocus;
    ui.shutterAFToggle.disabled = !canChangeShutterAF();
    ui.shutterAFDescription.textContent = t(!shutterAFAllowed ? "shutterAFUnavailable" :
      state.shutterAutofocus ? "shutterAFOn" : "shutterAFOff");
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
        ? state.localVideoBusy || !localLiveSupported || (shutterReleaseUnconfirmed() && !state.localVideoActive)
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
      if (command === "setting") {
        const setting = settingByKey(button.dataset.settingKey);
        const input = button.parentElement?.querySelector('input[type="text"]');
        const value = input?.value ?? "";
        if (input) input.disabled = interactionBusy || bulbActive;
        button.disabled = interactionBusy || bulbActive || !setting
          || !textSettingValueValid(setting, value)
          || value === setting.value;
        return;
      }
      const supported = command === "sleep"
        ? featureSupported(FEATURES.CAMERA_SLEEP)
        : command === "sensor-cleaning"
          ? featureSupported(FEATURES.SENSOR_CLEANING)
          : command === "directory"
            ? featureSupported(FEATURES.DIRECTORY_CONTROL)
            : featureSupported(FEATURES.CAMERA_CLOCK_SYNC);
      button.disabled = interactionBusy || bulbActive || !supported ||
        ((command === "sleep" || command === "sensor-cleaning") && (recording || stopOnly));
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
    ui.mediaUploadButton.hidden = !featureSupported(FEATURES.MEDIA_UPLOAD);
    ui.mediaUploadButton.disabled = !connected || interactionBusy || !featureSupported(FEATURES.MEDIA_UPLOAD);
    ui.mediaRefreshButton.disabled = !connected || interactionBusy;
    renderMediaTransfer();
    renderLatestMedia();
    renderMediaDetails();
    if (state.mediaAutoImportThumbnailsDeferred && !cameraInteractionBusy()) retryMediaAutoImportThumbnails();
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

  function refreshMedia({ preservePreview = false } = {}) {
    if (state.mediaRefreshPromise) {
      // An explicit refresh still dismisses review when joining a background read.
      if (!preservePreview) closeMediaPreview();
      return state.mediaRefreshPromise;
    }
    const promise = refreshMediaWhenCurrent({ preservePreview });
    state.mediaRefreshPromise = promise;
    return promise.finally(() => {
      if (state.mediaRefreshPromise === promise) state.mediaRefreshPromise = null;
      renderAvailability();
    });
  }

  async function refreshMediaWhenCurrent({ preservePreview = false } = {}) {
    while (state.session && featureSupported(FEATURES.MEDIA_BROWSER)) {
      while (cameraInteractionBusy() && state.session) await sleep(25);
      if (!state.session || !featureSupported(FEATURES.MEDIA_BROWSER)) return false;
      const rawSessionId = state.session.id;
      const interactionGeneration = state.refreshGeneration;
      const mediaScope = state.mediaScope;
      ui.mediaRefreshButton.disabled = true;
      state.mediaLoadStatus = "LOADING";
      renderMediaSummary();
      // A contents event owns the listing, not the independently opened review.
      if (!preservePreview) closeMediaPreview();
      try {
        const limitQuery = mediaScope === "recent" ? "?limit=61" : "";
        const response = await api(
          `/v1/session/${encodeURIComponent(rawSessionId)}/media${limitQuery}`,
        );
        if (
          state.session?.id !== rawSessionId ||
          state.refreshGeneration !== interactionGeneration ||
          state.mediaScope !== mediaScope
        ) continue;
        clearMediaThumbnails();
        const items = response.items || [];
        state.mediaHasMore = mediaScope === "recent" && items.length > 60;
        state.media = mediaScope === "recent" ? items.slice(0, 60) : items;
        state.mediaLoaded = true;
        state.mediaLoadStatus = "COMPLETE";
        renderMedia();
        if (preservePreview && ui.mediaPreviewDialog.open) renderMediaPreviewNavigation();
        return true;
      } catch (error) {
        if (state.session?.id !== rawSessionId) return false;
        if (
          state.refreshGeneration !== interactionGeneration ||
          state.mediaScope !== mediaScope
        ) continue;
        const normalized = captureError(error);
        state.mediaLoadStatus = "FAILED";
        renderMediaSummary();
        showToast(normalized.message, true);
        return false;
      }
    }
    return false;
  }

  function renderMedia() {
    if (!ui.mediaList) return;
    state.mediaSelection.replaceLoaded(state.media);
    mediaThumbnailObserver?.disconnect();
    const visibleItems = displayedMedia();
    const mediaPage = mediaLibrary.page(visibleItems, state.mediaPage, MEDIA_PAGE_SIZE);
    state.mediaPage = mediaPage.pageIndex;
    mediaPage.items.forEach((item) => mediaLibrary.touch(state.mediaThumbnailUrls, item.id));
    renderMediaSummary(visibleItems);
    renderMediaSelection(visibleItems);
    ui.mediaSortSelect.value = state.mediaSort;
    ui.mediaScopeControl.querySelectorAll("[data-media-scope]").forEach((button) => {
      const active = button.dataset.mediaScope === state.mediaScope;
      button.classList.toggle("active", active);
      button.setAttribute("aria-pressed", String(active));
    });
    ui.mediaPagination.hidden = mediaPage.pageCount <= 1;
    ui.mediaPageStatus.textContent = t("mediaPageStatus", mediaPage);
    ui.mediaPagePrevious.disabled = mediaPage.pageIndex === 0;
    ui.mediaPageNext.disabled = mediaPage.pageIndex >= mediaPage.pageCount - 1;
    ui.mediaFilterControl.querySelectorAll("[data-media-filter]").forEach((button) => {
      const active = button.dataset.mediaFilter === state.mediaFilter;
      button.classList.toggle("active", active);
      button.setAttribute("aria-pressed", String(active));
    });
    ui.mediaList.replaceChildren();
    if (!visibleItems.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = t(state.media.length ? "mediaNoMatches" : "mediaEmpty");
      ui.mediaList.append(empty);
      return;
    }
    let previousDate = null;
    mediaPage.items.forEach((item) => {
      const date = mediaDateGroup(item);
      if (date !== previousDate) {
        const heading = document.createElement("h3");
        heading.className = "media-date-heading";
        heading.textContent = date;
        ui.mediaList.append(heading);
        previousDate = date;
      }
      const card = document.createElement("article");
      card.className = "media-card";
      const selection = document.createElement("label");
      selection.className = "media-select";
      selection.hidden = !featureSupported(FEATURES.MEDIA_DOWNLOAD);
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.className = "media-select-checkbox";
      checkbox.dataset.mediaId = item.id;
      checkbox.checked = state.mediaSelection.has(item.id);
      checkbox.disabled = cameraInteractionBusy();
      const selectionLabel = t("mediaSelect").replaceAll("{name}", () => String(item.name));
      checkbox.setAttribute("aria-label", selectionLabel);
      checkbox.addEventListener("change", () => {
        if (cameraInteractionBusy()) return;
        state.mediaSelection.setSelected(item.id, checkbox.checked);
        renderMediaSelection();
      });
      const selectionText = document.createElement("span");
      selectionText.textContent = selectionLabel;
      selection.append(checkbox, selectionText);
      const previewSupported = mediaIsVideo(item)
        ? featureSupported(FEATURES.MEDIA_DOWNLOAD)
        : item.previewAvailable === true && featureSupported(FEATURES.MEDIA_PREVIEW);
      const thumbnail = document.createElement(previewSupported ? "button" : "div");
      thumbnail.className = "media-thumbnail media-card-preview";
      thumbnail.dataset.mediaId = item.id;
      if (previewSupported) {
        thumbnail.type = "button";
        thumbnail.disabled = Boolean(state.mediaBatchOwner);
        thumbnail.setAttribute("aria-label", t("previewMedia", { name: item.name }));
        thumbnail.addEventListener("click", () => openMediaPreview(item));
      }
      renderMediaThumbnail(thumbnail, item, state.mediaThumbnailUrls.get(item.id));
      const copy = document.createElement("div");
      copy.className = "media-copy";
      const name = document.createElement("strong");
      name.textContent = item.name;
      const time = document.createElement("span");
      time.textContent = formatMediaTime(item.captureTime) || String(item.kind).toUpperCase();
      copy.append(name, time);
      const size = document.createElement("span");
      size.className = "media-size";
      size.textContent = [formatMediaDimensions(item), formatMediaSize(item)].filter(Boolean).join(" · ");
      const actions = document.createElement("div");
      actions.className = "media-actions";
      const actionSupported = featureSupported(FEATURES.MEDIA_BROWSER) || mediaMetadataSupported() ||
        featureSupported(FEATURES.MEDIA_DOWNLOAD) ||
        featureSupported(FEATURES.MEDIA_DELETE);
      if (actionSupported) {
        const manage = document.createElement("button");
        manage.type = "button";
        manage.className = "icon-button";
        manage.dataset.tooltip = t("mediaDetails");
        manage.setAttribute("aria-label", t("manageMedia", { name: item.name }));
        const manageIcon = document.createElement("span");
        manageIcon.className = "icon";
        manageIcon.dataset.icon = "ellipsis-vertical";
        manage.append(manageIcon);
        manage.disabled = cameraInteractionBusy();
        manage.addEventListener("click", () => openMediaDetails(item));
        actions.append(manage);
      }
      card.append(selection, thumbnail, copy, size, actions);
      ui.mediaList.append(card);
      if (
        featureSupported(FEATURES.MEDIA_THUMBNAIL) &&
        !(mediaIsVideo(item) && String(item.id).startsWith("gphoto2-host:")) &&
        !state.mediaThumbnailUrls.has(item.id)
      ) {
        observeMediaThumbnail(thumbnail);
      }
    });
    window.OpenEosIcons?.render(ui.mediaList);
  }

  function mediaIsVideo(item) {
    return mediaLibrary.isVideo(item);
  }

  function renderMediaSelection(visibleItems = displayedMedia()) {
    if (!ui.mediaSelection || !state.mediaSelection) return;
    const selection = state.mediaSelection.snapshot(visibleItems.map((item) => item.id));
    const supported = featureSupported(FEATURES.MEDIA_DOWNLOAD);
    const folderAvailable = typeof window.showDirectoryPicker === "function";
    const busy = cameraInteractionBusy();
    ui.mediaSelection.hidden = !supported;
    ui.mediaSelectionSummary.textContent = t("mediaSelectionSummary", {
      selected: selection.length,
      hidden: selection.filter((entry) => entry.loaded && entry.hidden).length,
      notLoaded: selection.filter((entry) => !entry.loaded).length,
    });
    ui.mediaSelectFiltered.disabled = !state.session || busy || !visibleItems.length;
    ui.mediaSelectionClear.disabled = busy || !selection.length;
    ui.mediaBatchStart.disabled = !state.session || !supported || busy || !selection.length || !folderAvailable;
    ui.mediaBatchUnavailable.hidden = folderAvailable;
    ui.mediaList.querySelectorAll(".media-select-checkbox").forEach((checkbox) => {
      checkbox.disabled = busy;
    });
  }

  let mediaHistoryWarning = false;
  let mediaHistoryViewGeneration = 0;
  let mediaHistoryClearOwner = null;

  async function mediaHistoryCall(operation, ...args) {
    let result;
    try {
      result = mediaHistoryStore && typeof mediaHistoryStore[operation] === "function"
        ? await mediaHistoryStore[operation](...args) : { ok: false, code: "unavailable" };
    } catch (_) {
      result = { ok: false, code: "unavailable" };
    }
    if (!result?.ok && !["stale-ticket", "missing-receipt"].includes(result?.code)) {
      mediaHistoryWarning = true;
      try {
        document.querySelectorAll("[data-media-history-warning]").forEach((node) => {
          node.hidden = false;
          node.textContent = t("mediaHistoryWarning");
        });
      } catch (_) { /* Optional history UI must not change transfer outcomes. */ }
    }
    return result || { ok: false, code: "unavailable" };
  }

  async function refreshMediaHistory() {
    const generation = ++mediaHistoryViewGeneration;
    if (!ui.mediaHistoryDialog?.open) return;
    ui.mediaHistoryStatus.textContent = t("mediaHistoryLoading");
    const result = await mediaHistoryCall("list");
    if (generation !== mediaHistoryViewGeneration || !ui.mediaHistoryDialog.open) return;
    ui.mediaHistoryList.replaceChildren();
    const plans = result.ok ? (result.plans || []) : [];
    const plannedIDs = new Set(plans.flatMap((plan) => plan.rows.map((row) => row.rowId)));
    const legacy = result.ok ? result.entries.filter((entry) => !plannedIDs.has(entry.receiptId)) : [];
    const rows = plans.flatMap((plan) => plan.rows.map((row, index) => ({
      entry: row.receipt || { filename: row.filename, outcome: "queued", startedAtMillis: plan.createdAtMillis },
      plan, position: index + 1,
    }))).concat(legacy.map((entry) => ({ entry, plan: null })));
    ui.mediaHistoryClear.disabled = !!mediaHistoryClearOwner || !result.ok || !rows.length;
    if (!result.ok) {
      ui.mediaHistoryStatus.textContent = t("mediaHistoryUnavailable");
      return;
    }
    ui.mediaHistoryStatus.textContent = rows.length ? t("mediaHistoryCount", { count: rows.length }) : t("mediaHistoryEmpty");
    const outcomeKeys = { completed: "mediaHistoryCompleted", failed: "mediaHistoryFailed", queued: "mediaHistoryQueued", "cancelled-or-unknown": "mediaHistoryUnconfirmed" };
    for (const { entry, plan, position } of rows) {
      const row = document.createElement("li");
      row.dataset.outcome = entry.outcome;
      if (plan) row.dataset.worklist = plan.batchId;
      const name = document.createElement("strong");
      name.textContent = entry.filename;
      const scope = document.createElement("span");
      scope.textContent = plan ? t("mediaHistoryWorklistPosition", { position, total: plan.rows.length, created: new Date(plan.createdAtMillis).toLocaleString(resolvedLanguage() === "zh-TW" ? "zh-TW" : "en") }) : t("mediaHistoryLegacyAttempt");
      const detail = document.createElement("span");
      detail.textContent = t(outcomeKeys[entry.outcome] || "mediaHistoryUnconfirmed");
      const when = document.createElement("time");
      when.dateTime = new Date(entry.startedAtMillis).toISOString();
      when.textContent = new Date(entry.startedAtMillis).toLocaleString(resolvedLanguage() === "zh-TW" ? "zh-TW" : "en");
      row.append(name, scope, detail, when);
      if (entry.mayRemain || entry.outcomeUnknown) {
        const warning = document.createElement("span");
        warning.textContent = t("mediaHistoryLeftover");
        row.append(warning);
      }
      ui.mediaHistoryList.append(row);
    }
  }

  function openMediaHistory() {
    if (ui.mediaHistoryDialog.open) return;
    ui.mediaHistoryConfirm.hidden = true;
    ui.mediaHistoryConfirmYes.disabled = false;
    ui.mediaHistoryDialog.showModal();
    void refreshMediaHistory().catch(() => {});
  }

  async function clearMediaHistory() {
    if (mediaHistoryClearOwner) return;
    const owner = {};
    mediaHistoryClearOwner = owner;
    ui.mediaHistoryConfirmYes.disabled = true;
    ui.mediaHistoryClear.disabled = true;
    const result = await mediaHistoryCall("clear");
    if (mediaHistoryClearOwner !== owner || !ui.mediaHistoryDialog.open) return;
    mediaHistoryClearOwner = null;
    ui.mediaHistoryConfirmYes.disabled = false;
    if (result.ok) {
      ui.mediaHistoryConfirm.hidden = true;
      ui.mediaHistoryClose.focus();
      await refreshMediaHistory();
    } else {
      ui.mediaHistoryClear.disabled = false;
      ui.mediaHistoryStatus.textContent = t("mediaHistoryClearFailed");
    }
  }

  function renderMediaBatch() {
    if (!ui.mediaBatchPanel) return;
    const batch = state.mediaBatch;
    ui.mediaBatchPanel.hidden = !batch;
    if (!batch) return;
    const owner = state.mediaBatchOwner;
    const counts = { completed: 0, failed: 0, uncertain: 0, notStarted: 0, queued: 0, running: 0 };
    const statusKeys = {
      pending: ["queued", "mediaBatchQueued"],
      running: ["running", "mediaBatchRunning"],
      completed: ["completed", "mediaBatchCompleted"],
      failed: ["failed", "mediaBatchFailed"],
      "cancelled-or-unknown": ["uncertain", "mediaBatchUncertain"],
      "not-started": ["notStarted", "mediaBatchNotStarted"],
    };
    ui.mediaBatchList.replaceChildren();
    batch.queue.results.forEach((result, index) => {
      const [countKey, labelKey] = statusKeys[result.status];
      counts[countKey] += 1;
      const row = document.createElement("li");
      row.dataset.mediaId = result.id;
      row.dataset.status = result.status;
      const name = document.createElement("strong");
      name.textContent = result.receipt?.name || batch.destination?.files?.find((file) => file.index === index)?.name || result.item.name;
      const status = document.createElement("span");
      status.textContent = t(labelKey);
      if (batch.progress?.index === index && result.status === "running") {
        status.textContent += ` · ${t("downloadProgressUnknown", { transferred: formatBytes(batch.progress.bytesTransferred) })}`;
      }
      row.append(name, status);
      if (result.error?.message) {
        const error = document.createElement("span");
        error.className = "supporting";
        error.textContent = result.error.message;
        row.append(error);
      }
      ui.mediaBatchList.append(row);
    });
    ui.mediaBatchDestination.textContent = batch.destinationName
      ? t("mediaBatchDestination").replaceAll("{name}", () => batch.destinationName) : t("mediaBatchChoosing");
    ui.mediaBatchSummary.textContent = t("mediaBatchSummary", counts);
    const notices = [t(owner?.cancelled ? "mediaBatchCancelling" : "mediaBatchNotice")];
    if (batch.errorMessage) notices.push(batch.errorMessage);
    if (batch.destination?.leftoverCount) {
      notices.push(t("mediaBatchLeftovers", { count: batch.destination.leftoverCount }));
    } else if (batch.destination?.directoryMayRemain && !counts.completed && !owner) {
      notices.push(t("mediaBatchDirectoryMayRemain"));
    }
    ui.mediaBatchNotice.textContent = notices.join(" ");
    ui.mediaBatchCancel.hidden = !owner;
    ui.mediaBatchCancel.disabled = !owner || owner.cancelled;
  }

  function ownsMediaBatch(owner) {
    return state.mediaBatchOwner === owner && state.session === owner.session;
  }

  function assertMediaBatchCurrent(owner) {
    if (!ownsMediaBatch(owner) || owner.cancelled || owner.controller.signal.aborted) {
      throw mediaTransfer.cancellationError();
    }
  }

  async function transferMediaBatchItem(item, { owner, destination, index }) {
    let writable = null;
    let closed = false;
    let historyReceipt = null;
    try {
      assertMediaBatchCurrent(owner);
      if (owner.historyTicket) {
        const recorded = owner.historyPlan
          ? await mediaHistoryCall("beginPlannedAttempt", owner.historyTicket, owner.historyPlan.rows[index].rowId)
          : await mediaHistoryCall("startAttempt", owner.historyTicket, { filename: item.name, destination: "browser-directory" });
        if (owner.historyPlan && !recorded.ok) {
          if (recorded.code === "stale-ticket") {
            // A deliberate clear retires the whole plan. Keep manual saving
            // available without rebuilding cleared rows in the new epoch.
            owner.historyPlan = null;
            owner.historyTicket = null;
          } else {
            // The planned row still says queued. Do not open a destination or
            // issue a GET unless its durable attempt admission is confirmed.
            const failure = new Error(t("mediaWorklistAdmissionFailed"));
            failure.transferNotStarted = true;
            throw failure;
          }
        }
        historyReceipt = recorded.ok ? recorded.receipt : null;
      }
      assertMediaBatchCurrent(owner);
      const file = await destination.open(item, index);
      writable = file.writable;
      // A directory/file prompt may finish after disconnect or cancellation.
      // Always abort its late writable before requesting any camera bytes.
      assertMediaBatchCurrent(owner);
      const response = await api(
        `/v1/session/${encodeURIComponent(owner.session.id)}/media/${encodeURIComponent(item.id)}`,
        { responseType: "response", signal: owner.controller.signal },
      );
      assertMediaBatchCurrent(owner);
      const result = await mediaTransfer.readResponse(response, {
        signal: owner.controller.signal,
        expectedBytes: item.sizeBytes,
        writeChunk: async (chunk) => {
          assertMediaBatchCurrent(owner);
          await writable.write(chunk);
          assertMediaBatchCurrent(owner);
        },
        onProgress: (progress) => {
          if (!ownsMediaBatch(owner) || owner.cancelled) return;
          state.mediaBatch.progress = { ...progress, index };
          scheduleMediaTransferRender();
        },
      });
      assertMediaBatchCurrent(owner);
      await writable.close();
      closed = true;
      if (historyReceipt) await mediaHistoryCall("finishAttempt", historyReceipt, {
        outcome: "completed", mayRemain: false, outcomeUnknown: false, bytesTransferred: result.bytesTransferred,
      });
      // Close completion is a receipt even if cancellation arrived during it.
      // The queue retains the receipt while the owner guards prevent stale UI.
      return { completed: true, bytesTransferred: result.bytesTransferred, name: file.name };
    } catch (error) {
      let cleanupError = null;
      if (writable && !closed) {
        try {
          await writable.abort(error);
        } catch (abortError) {
          cleanupError = abortError;
        }
      }
      const fileState = destination.snapshot().files.find((file) => file.index === index);
      const uncertain = !!cleanupError || !!fileState?.outcomeUnknown;
      if (historyReceipt) await mediaHistoryCall("finishAttempt", historyReceipt, {
        outcome: uncertain || owner.cancelled || mediaTransfer.isAbortError(error) ? "cancelled-or-unknown" : "failed",
        mayRemain: !!fileState && !closed, outcomeUnknown: uncertain,
      });
      if (uncertain) {
        // Preserve the primary failure while making uncertain cleanup visible
        // to the queue, including errors which are primitive or frozen.
        const failure = new Error(error?.message || String(error));
        failure.name = error?.name || "Error";
        failure.cause = error;
        failure.outcomeUnknown = true;
        if (cleanupError) failure.abortError = cleanupError;
        throw failure;
      }
      throw error;
    } finally {
      if (ownsMediaBatch(owner)) state.mediaBatch.destination = destination.snapshot();
      if (ui.mediaHistoryDialog?.open) void refreshMediaHistory().catch(() => {});
    }
  }

  async function downloadSelectedMedia() {
    if (!state.session || !featureSupported(FEATURES.MEDIA_DOWNLOAD) || cameraInteractionBusy()) return;
    if (typeof window.showDirectoryPicker !== "function") {
      showToast(t("mediaBatchUnavailable"), true);
      return;
    }
    state.mediaSelection.replaceLoaded(state.media);
    const items = state.mediaSelection.snapshot().map((entry) => entry.item);
    if (!items.length) return;
    const owner = { session: state.session, controller: new AbortController(), cancelled: false, silent: false };
    state.mediaBatchOwner = owner;
    state.refreshGeneration += 1;
    const batch = {
      queue: { status: "pending", results: items.map((item) => ({ id: item.id, item, status: "pending" })) },
      destinationName: "", destination: null, progress: null, errorMessage: null,
    };
    state.mediaBatch = batch;
    renderMedia();
    renderAvailability();
    renderMediaPreviewNavigation();
    setOperationState(t("mediaBatchChoosing"));
    let destination = null;
    try {
      // Invoke directly in the user's click task; never restore/persist a handle
      // or fall back to automatic anchor downloads when folder access fails.
      const directoryHandle = await window.showDirectoryPicker({ mode: "readwrite" });
      assertMediaBatchCurrent(owner);
      const planned = typeof mediaHistoryStore?.beginPlannedBatch === "function";
      const history = planned
        ? await mediaHistoryCall("beginPlannedBatch", items.map((item) => item.name))
        : await mediaHistoryCall("createBatchTicket");
      assertMediaBatchCurrent(owner);
      if (planned && !history.ok && history.planAbsent !== true) {
        // IndexedDB may have committed before losing its acknowledgement.
        // An unknown plan result must not fall through to unrecorded saving.
        throw new Error(t("mediaWorklistAdmissionUnknown"));
      }
      owner.historyTicket = history.ok ? history.ticket : null;
      owner.historyPlan = planned && history.ok ? history.ticket : null;
      destination = mediaBatchDestination.createDirectoryBatchDestination({
        directoryHandle,
        nonce: globalThis.crypto.randomUUID(),
        safeDownloadName: mediaTransfer.safeDownloadName,
        signal: owner.controller.signal,
        isCurrent: () => ownsMediaBatch(owner),
      });
      batch.destination = destination.snapshot();
      batch.destinationName = `${directoryHandle.name} / ${batch.destination.batchName}`;
      setOperationState(t("mediaBatchRunning"));
      const result = await mediaBatch.runSequentialBatch(items, {
        signal: owner.controller.signal,
        isCurrent: () => ownsMediaBatch(owner),
        transfer: (item, { index }) => transferMediaBatchItem(item, { owner, destination, index }),
        onUpdate: (queue) => {
          if (!ownsMediaBatch(owner)) return;
          batch.queue = queue;
          batch.destination = destination.snapshot();
          renderMediaBatch();
        },
      });
      if (!ownsMediaBatch(owner)) return;
      batch.queue = result;
      batch.destination = destination.snapshot();
      if (!owner.silent) {
        setOperationState(t(batch.queue.status === "completed" ? "mediaBatchFinished" : "mediaBatchStopped"));
      }
    } catch (error) {
      if (!ownsMediaBatch(owner)) return;
      batch.queue = { status: "stopped", results: batch.queue.results.map((result) =>
        result.status === "pending" ? { ...result, status: "not-started" } : result) };
      const cancelled = owner.cancelled || mediaTransfer.isAbortError(error);
      if (!cancelled) batch.errorMessage = captureError(error).message;
      if (!owner.silent) {
        setOperationState(cancelled ? t("mediaBatchStopped") : batch.errorMessage, !cancelled);
        if (!cancelled) showToast(batch.errorMessage, true);
      }
    } finally {
      if (ownsMediaBatch(owner)) {
        if (destination) batch.destination = destination.snapshot();
        clearScheduledMediaTransferRender();
        state.mediaBatchOwner = null;
        renderMedia();
        renderAvailability();
        renderMediaPreviewNavigation();
      }
    }
  }

  function cancelMediaBatch({ silent = false } = {}) {
    const owner = state.mediaBatchOwner;
    if (!owner || !ownsMediaBatch(owner)) return;
    owner.cancelled = true;
    owner.silent = silent;
    if (state.mediaBatch.queue.status === "pending") {
      state.mediaBatch.queue = { status: "stopping", results: state.mediaBatch.queue.results.map((result) =>
        result.status === "pending" ? { ...result, status: "not-started" } : result) };
    }
    owner.controller.abort();
    renderMediaBatch();
  }

  function mediaAutoImportEligible() {
    return Boolean(state.session?.engine === "ccapi" &&
      featureSupported(FEATURES.MEDIA_BROWSER) && featureSupported(FEATURES.MEDIA_DOWNLOAD) &&
      !state.status?.recording && !recordingRecoveryActive() && !bulbControlLocked());
  }

  function ownsMediaAutoImport(owner) {
    return state.mediaAutoImportOwner === owner && state.session === owner.session;
  }

  function mediaAutoImportManualBusy(owner) {
    return Boolean(state.busy || mediaTransferActive() || state.mediaRefreshPromise ||
      state.mediaLoadStatus === "LOADING" || state.latestMediaRefreshPromise ||
      state.latestMediaThumbnailLoading || state.mediaPreviewItem || state.mediaDetailsBusy ||
      state.mediaThumbnailLoads?.size || (state.mediaAutoImportReading && state.mediaAutoImportReading !== owner));
  }

  function assertMediaAutoImportCurrent(owner) {
    if (!ownsMediaAutoImport(owner) || owner.cancelled || owner.controller.signal.aborted ||
        document.visibilityState !== "visible" || !mediaAutoImportEligible()) {
      throw mediaTransfer.cancellationError();
    }
  }

  async function withMediaAutoImportRead(owner, work) {
    assertMediaAutoImportCurrent(owner);
    if (mediaAutoImportManualBusy(owner)) throw new Error(t("mediaAutoImportBusy"));
    let releaseRead;
    owner.readSettled = new Promise((resolve) => { releaseRead = resolve; });
    state.mediaAutoImportReading = owner;
    try {
      renderAvailability();
      renderMediaPreviewNavigation();
      assertMediaAutoImportCurrent(owner);
      return await work();
    } finally {
      releaseRead();
      if (state.mediaAutoImportReading === owner) {
        state.mediaAutoImportReading = null;
        // Release only this reservation, then refresh the current connection's
        // controls. A retired owner never publishes its old view or history.
        renderAvailability();
        renderMediaPreviewNavigation();
      }
    }
  }

  function requireMediaAutoImportResponse(response, header, value) {
    if (response.status !== 200 || response.redirected || response.headers.get("content-range") !== null ||
        response.headers.get(header) !== value) {
      throw new Error(t("mediaAutoImportProtocol"));
    }
  }

  async function readMediaAutoImportInventory(owner, { signal, limit }) {
    return withMediaAutoImportRead(owner, async () => {
      const response = await api(`/v1/session/${encodeURIComponent(owner.session.id)}/media?inventory=strict&limit=${limit}`, {
        responseType: "response", signal, redirect: "error",
      });
      assertMediaAutoImportCurrent(owner);
      requireMediaAutoImportResponse(response, "X-Open-EOS-Inventory", "complete-v1");
      const data = await response.json();
      assertMediaAutoImportCurrent(owner);
      return { complete: true, items: data.items };
    });
  }

  async function readMediaAutoImportMetadata(owner, item, { signal }) {
    return withMediaAutoImportRead(owner, async () => {
      const response = await api(`/v1/session/${encodeURIComponent(owner.session.id)}/media/${encodeURIComponent(item.id)}/info?fresh=true`, {
        responseType: "response", signal, redirect: "error",
      });
      assertMediaAutoImportCurrent(owner);
      requireMediaAutoImportResponse(response, "X-Open-EOS-Fresh-Metadata", "v1");
      const updated = await response.json();
      assertMediaAutoImportCurrent(owner);
      return { fresh: true, item: updated };
    });
  }

  async function mediaAutoImportHistoryCall(owner, operation, ...args) {
    let result;
    try {
      result = mediaHistoryStore && typeof mediaHistoryStore[operation] === "function"
        ? await mediaHistoryStore[operation](...args) : { ok: false, code: "unavailable" };
    } catch (_) { result = { ok: false, code: "unavailable" }; }
    if (ownsMediaAutoImport(owner) && !result?.ok && !["stale-ticket", "missing-receipt"].includes(result?.code)) {
      mediaHistoryWarning = true;
      try {
        document.querySelectorAll("[data-media-history-warning]").forEach((node) => {
          node.hidden = false;
          node.textContent = t("mediaHistoryWarning");
        });
      } catch (_) { /* Optional history rendering never changes a close receipt. */ }
    }
    return result || { ok: false, code: "unavailable" };
  }

  async function transferMediaAutoImportItem(owner, item, { signal, expectedBytes }) {
    return withMediaAutoImportRead(owner, async () => {
      const destination = owner.destination;
      const index = owner.nextFileIndex++;
      let writable = null, closed = false, historyReceipt = null;
      try {
        assertMediaAutoImportCurrent(owner);
        if (!Number.isSafeInteger(expectedBytes) || expectedBytes <= 0 || expectedBytes !== item.sizeBytes) {
          throw new Error(t("mediaAutoImportProtocol"));
        }
        if (!owner.historyStarted) {
          owner.historyStarted = true;
          const history = await mediaAutoImportHistoryCall(owner, "createBatchTicket");
          owner.historyTicket = history.ok ? history.ticket : null;
        }
        assertMediaAutoImportCurrent(owner);
        if (owner.historyTicket) {
          const history = await mediaAutoImportHistoryCall(owner, "startAttempt", owner.historyTicket, { filename: item.name, destination: "browser-directory" });
          historyReceipt = history.ok ? history.receipt : null;
        }
        assertMediaAutoImportCurrent(owner);
        const file = await destination.open(item, index);
        writable = file.writable;
        assertMediaAutoImportCurrent(owner);
        const response = await api(`/v1/session/${encodeURIComponent(owner.session.id)}/media/${encodeURIComponent(item.id)}?attempt=single`, {
          responseType: "response", signal, redirect: "error",
        });
        assertMediaAutoImportCurrent(owner);
        requireMediaAutoImportResponse(response, "X-Open-EOS-Original-Attempt", "single-v1");
        const contentLength = response.headers.get("content-length");
        if (contentLength !== null && (!/^\d+$/.test(contentLength) || Number(contentLength) !== expectedBytes)) {
          throw mediaTransfer.lengthMismatchError(expectedBytes, Number(contentLength));
        }
        // The independently observed stable size is authoritative, even when an
        // older proxy supplies a different Content-Length or no length at all.
        let received = 0;
        const result = await mediaTransfer.readResponse(response, {
          signal, expectedBytes,
          writeChunk: async (chunk) => {
            assertMediaAutoImportCurrent(owner);
            if (chunk.byteLength > expectedBytes - received) {
              throw mediaTransfer.lengthMismatchError(expectedBytes, received + chunk.byteLength);
            }
            await writable.write(chunk);
            received += chunk.byteLength;
            assertMediaAutoImportCurrent(owner);
          },
        });
        if (received !== expectedBytes || result.bytesTransferred !== expectedBytes) {
          throw mediaTransfer.lengthMismatchError(expectedBytes, received);
        }
        assertMediaAutoImportCurrent(owner);
        await writable.close();
        closed = true;
        if (historyReceipt) await mediaAutoImportHistoryCall(owner, "finishAttempt", historyReceipt, {
          outcome: "completed", mayRemain: false, outcomeUnknown: false, bytesTransferred: received,
        });
        // A successful close remains a receipt after Stop or session retirement.
        return { completed: true, bytesTransferred: received, name: file.name };
      } catch (error) {
        let cleanupError = null;
        if (writable && !closed) {
          try { await writable.abort(error); } catch (failure) { cleanupError = failure; }
        }
        const file = destination.snapshot().files.find((entry) => entry.index === index);
        const unknown = Boolean(cleanupError || file?.outcomeUnknown);
        if (historyReceipt) await mediaAutoImportHistoryCall(owner, "finishAttempt", historyReceipt, {
          outcome: unknown || owner.cancelled || mediaTransfer.isAbortError(error) ? "cancelled-or-unknown" : "failed",
          mayRemain: Boolean(file && !closed), outcomeUnknown: unknown,
        });
        if (unknown) {
          const failure = new Error(error?.message || String(error));
          failure.name = error?.name || "Error";
          failure.cause = error;
          failure.outcomeUnknown = true;
          throw failure;
        }
        throw error;
      } finally {
        if (ownsMediaAutoImport(owner)) owner.view.destination = destination.snapshot();
        if (ownsMediaAutoImport(owner) && ui.mediaHistoryDialog?.open) void refreshMediaHistory().catch(() => {});
      }
    });
  }

  function finishMediaAutoImportOwner(owner) {
    if (!ownsMediaAutoImport(owner) || owner.choosing || owner.running || state.mediaAutoImportReading === owner) return;
    if (!owner.cancelled && owner.policy?.snapshot().phase !== "stopped") return;
    if (owner.timer !== null) window.clearTimeout(owner.timer);
    owner.timer = null;
    owner.controller.abort();
    state.mediaAutoImportOwner = null;
    renderMediaAutoImport();
    renderAvailability();
  }

  function scheduleMediaAutoImport(owner) {
    if (!ownsMediaAutoImport(owner) || owner.cancelled || owner.timer !== null || owner.running) return;
    // The policy owns the 5-second inventory cadence. This foreground scheduler
    // only offers sequential steps, at least one second apart, without event fanout.
    owner.timer = window.setTimeout(() => {
      owner.timer = null;
      void stepMediaAutoImport(owner);
    }, 1000);
  }

  async function stepMediaAutoImport(owner) {
    if (!ownsMediaAutoImport(owner) || owner.cancelled || owner.running) return;
    if (document.visibilityState !== "visible" || !mediaAutoImportEligible()) {
      stopMediaAutoImport(document.visibilityState !== "visible" ? "backgrounded" : "camera-unavailable");
      return;
    }
    owner.running = true;
    try {
      await owner.policy.step();
    } finally {
      owner.running = false;
      if (ownsMediaAutoImport(owner)) {
        owner.view.snapshot = owner.policy.snapshot();
        renderMediaAutoImport();
        finishMediaAutoImportOwner(owner);
        scheduleMediaAutoImport(owner);
      }
    }
  }

  async function startMediaAutoImport() {
    if (!mediaAutoImport || !mediaAutoImportEligible() || document.visibilityState !== "visible" ||
        state.mediaAutoImportOwner || mediaAutoImportManualBusy(null)) return;
    if (typeof window.showDirectoryPicker !== "function" || typeof globalThis.crypto?.randomUUID !== "function") return;
    const owner = { session: state.session, controller: new AbortController(), cancelled: false,
      choosing: true, running: false, timer: null, policy: null, destination: null, nextFileIndex: 0,
      view: { destinationName: "", destination: null, snapshot: { phase: "choosing", reason: null, completed: 0, pendingCount: 0, knownCount: 0, results: [] } } };
    state.mediaAutoImportOwner = owner;
    state.mediaAutoImport = owner.view;
    renderMediaAutoImport();
    try {
      // Must be invoked in the initiating click task, before any awaited work.
      const directoryHandle = await window.showDirectoryPicker({ mode: "readwrite" });
      assertMediaAutoImportCurrent(owner);
      owner.destination = mediaBatchDestination.createDirectoryBatchDestination({ directoryHandle,
        nonce: globalThis.crypto.randomUUID(), safeDownloadName: mediaTransfer.safeDownloadName,
        signal: owner.controller.signal, isCurrent: () => ownsMediaAutoImport(owner),
      });
      owner.view.destination = owner.destination.snapshot();
      owner.view.destinationName = `${directoryHandle.name} / ${owner.view.destination.batchName}`;
      owner.policy = mediaAutoImport.createController({
        inventory: (options) => readMediaAutoImportInventory(owner, options),
        metadata: (item, options) => readMediaAutoImportMetadata(owner, item, options),
        transfer: (item, options) => transferMediaAutoImportItem(owner, item, options),
        isCurrent: () => ownsMediaAutoImport(owner),
        isForeground: () => document.visibilityState === "visible" && mediaAutoImportEligible(),
        isBusy: () => mediaAutoImportManualBusy(owner),
        now: () => performance.now(),
        onUpdate: (snapshot) => {
          if (!ownsMediaAutoImport(owner)) return;
          owner.view.snapshot = snapshot;
          renderMediaAutoImport();
        },
      });
      owner.view.snapshot = owner.policy.snapshot();
    } catch (error) {
      if (!ownsMediaAutoImport(owner)) return;
      owner.cancelled = true;
      owner.view.snapshot = { ...owner.view.snapshot, phase: "stopped",
        reason: owner.view.snapshot.reason || (mediaTransfer.isAbortError(error) ? "user-stopped" : "destination-unavailable") };
    } finally {
      owner.choosing = false;
      if (ownsMediaAutoImport(owner)) {
        renderMediaAutoImport();
        finishMediaAutoImportOwner(owner);
        scheduleMediaAutoImport(owner);
      }
    }
  }

  function stopMediaAutoImport(reason = "user-stopped") {
    const owner = state.mediaAutoImportOwner;
    if (!owner || !ownsMediaAutoImport(owner)) return;
    owner.cancelled = true;
    if (owner.timer !== null) window.clearTimeout(owner.timer);
    owner.timer = null;
    owner.controller.abort();
    owner.view.snapshot = owner.policy ? owner.policy.stop(reason) : {
      ...owner.view.snapshot, phase: owner.choosing ? "stopping" : "stopped", reason,
    };
    renderMediaAutoImport();
    finishMediaAutoImportOwner(owner);
  }

  function renderMediaAutoImport() {
    if (!ui.mediaAutoImportPanel) return;
    const owner = state.mediaAutoImportOwner;
    const view = state.mediaAutoImport;
    const snapshot = view?.snapshot;
    ui.mediaAutoImportPanel.dataset.phase = snapshot?.phase || "off";
    ui.mediaAutoImportPanel.dataset.operation = snapshot?.operation || "";
    const available = Boolean(mediaAutoImport && typeof window.showDirectoryPicker === "function" && typeof globalThis.crypto?.randomUUID === "function");
    ui.mediaAutoImportStart.disabled = !available || !mediaAutoImportEligible() || Boolean(owner) ||
      document.visibilityState !== "visible" || mediaAutoImportManualBusy(null);
    ui.mediaAutoImportUnavailable.hidden = available;
    ui.mediaAutoImportStop.hidden = !owner;
    ui.mediaAutoImportStop.disabled = !owner || owner.cancelled;
    const phases = { choosing: "mediaAutoImportChoosing", "baseline-first": "mediaAutoImportBaseline",
      "baseline-confirm": "mediaAutoImportBaseline", watching: "mediaAutoImportWatching",
      stopping: "mediaAutoImportStopping", stopped: "mediaAutoImportStopped" };
    const operations = { inventory: "mediaAutoImportInventory", metadata: "mediaAutoImportMetadata", transfer: "mediaAutoImportSaving" };
    const operationKey = !snapshot?.reason && operations[snapshot?.operation];
    ui.mediaAutoImportStatus.textContent = t(operationKey || phases[snapshot?.phase] || "mediaAutoImportOff");
    ui.mediaAutoImportDestination.textContent = view?.destinationName ? t("mediaBatchDestination", { name: view.destinationName }) : "";
    ui.mediaAutoImportSummary.textContent = t("mediaAutoImportSummary", {
      saved: snapshot?.completed || 0, pending: snapshot?.pendingCount || 0,
      known: snapshot?.knownCount || 0, results: snapshot?.results.length || 0,
    });
    const reasons = {
      "user-stopped": "mediaAutoImportUserStopped", backgrounded: "mediaAutoImportBackgrounded",
      disconnected: "mediaAutoImportDisconnected", "session-retired": "mediaAutoImportDisconnected",
      "camera-unavailable": "mediaAutoImportCameraUnavailable", "baseline-changed": "mediaAutoImportBaselineChanged",
      "identity-limit": "mediaAutoImportLimit", "queue-limit": "mediaAutoImportLimit",
      "pending-disappeared": "mediaAutoImportDisappeared", "metadata-deadline": "mediaAutoImportMetadataDeadline",
      "inventory-unavailable": "mediaAutoImportInventoryUnavailable", "metadata-unavailable": "mediaAutoImportMetadataUnavailable",
      "fresh-metadata-unavailable": "mediaAutoImportMetadataUnavailable", "transfer-unconfirmed": "mediaAutoImportUnconfirmed",
      "transfer-failed": "mediaAutoImportFailed", "destination-unavailable": "mediaAutoImportDestinationUnavailable",
    };
    ui.mediaAutoImportReason.hidden = !snapshot?.reason;
    ui.mediaAutoImportReason.textContent = snapshot?.reason ? t(reasons[snapshot.reason] || "mediaAutoImportUnknown", { reason: snapshot.reason }) : "";
    if (view?.destination?.leftoverCount) ui.mediaAutoImportReason.textContent += " " + t("mediaBatchLeftovers", { count: view.destination.leftoverCount });
    ui.mediaAutoImportList.replaceChildren();
    const labels = { running: "mediaBatchRunning", completed: "mediaBatchCompleted", failed: "mediaBatchFailed", "cancelled-or-unknown": "mediaBatchUncertain" };
    for (const result of snapshot?.results || []) {
      const row = document.createElement("li");
      row.dataset.status = result.status;
      row.dataset.id = result.id;
      const name = document.createElement("strong");
      name.textContent = result.name;
      const status = document.createElement("span");
      status.textContent = t(labels[result.status] || "mediaBatchUncertain");
      row.append(name, status);
      ui.mediaAutoImportList.append(row);
    }
  }

  function mediaTime(item) {
    return mediaLibrary.mediaTime(item);
  }

  function naturalMediaName(left, right) {
    return mediaLibrary.naturalName(left, right, resolvedLanguage());
  }

  function displayedMedia() {
    return mediaLibrary.itemsForDisplay(
      state.media,
      state.mediaFilter,
      state.mediaSort,
      resolvedLanguage(),
      state.mediaDateRange,
      state.mediaRatingFilter,
      state.mediaFolderFilter,
    );
  }

  function renderMediaSummary(visibleItems = displayedMedia()) {
    renderMediaDateFilter(visibleItems);
    renderMediaRatingFilter(visibleItems);
    renderMediaFolderFilter(visibleItems);
    if (!ui.mediaSummary) return;
    ui.mediaSummary.dataset.loadStatus = state.mediaLoadStatus;
    if (state.mediaLoadStatus === "LOADING") {
      ui.mediaSummary.textContent = t("mediaLoadingCount", { count: state.media.length });
      return;
    }
    if (state.mediaLoadStatus === "FAILED") {
      ui.mediaSummary.textContent = t("mediaFailedCount", { count: state.media.length });
      return;
    }
    if (state.mediaLoadStatus !== "COMPLETE") {
      ui.mediaSummary.textContent = t("mediaNotLoadedCount");
      return;
    }
    if (state.mediaScope === "recent") {
      ui.mediaSummary.textContent = t(
        state.mediaHasMore ? "mediaRecentMoreCount" : "mediaRecentCount",
        { count: state.media.length },
      );
      return;
    }
    ui.mediaSummary.textContent = visibleItems.length === state.media.length
      ? t("mediaCount", { count: state.media.length })
      : t("mediaFilteredCount", { visible: visibleItems.length, total: state.media.length });
  }

  function mediaDisplayTimeZone() {
    return new Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  }

  function renderMediaDateFilter(visibleItems = displayedMedia()) {
    if (!ui.mediaDateButton) return;
    const range = state.mediaDateRange;
    ui.mediaDateButton.disabled = !state.session || !featureSupported(FEATURES.MEDIA_BROWSER);
    ui.mediaDateButton.setAttribute("aria-pressed", String(Boolean(range)));
    ui.mediaDateClear.hidden = !range;
    ui.mediaDateSummary.hidden = !range;
    ui.mediaDateZone.textContent = t("mediaDateZone", { zone: mediaDisplayTimeZone() });
    if (range) {
      ui.mediaDateSummary.textContent = t("mediaDateActive", {
        start: range.start, end: range.end, matched: visibleItems.length,
        total: state.media.length, unknown: mediaLibrary.countUnknownDates(state.media),
      }) + " " + t("mediaDateZone", { zone: mediaDisplayTimeZone() });
    }
  }

  function mediaFolderChoiceLabel(folder, folders) {
    const duplicates = folders.filter(candidate => candidate.label === folder.label);
    const index = duplicates.findIndex(candidate => candidate.id === folder.id);
    return duplicates.length > 1
      ? folder.label + " · " + t("mediaFolderDuplicate", { index: index + 1 }) : folder.label;
  }

  function renderMediaFolderFilter(visibleItems = displayedMedia()) {
    if (!ui.mediaFolderButton) return;
    const filter = state.mediaFolderFilter || { kind: "all" };
    const active = filter.kind !== "all";
    ui.mediaFolderButton.disabled = !state.session || !featureSupported(FEATURES.MEDIA_BROWSER);
    ui.mediaFolderButton.setAttribute("aria-pressed", String(active));
    ui.mediaFolderClear.hidden = !active;
    ui.mediaFolderSummary.hidden = !active;
    if (active) {
      const folders = mediaLibrary.folderOptions(state.media);
      const current = folders.find(folder => folder.id === filter.id);
      const label = filter.kind === "unknown" ? t("mediaFolderUnknown")
        : current ? mediaFolderChoiceLabel(current, folders) : filter.label + " · " + t("mediaFolderMissing");
      ui.mediaFolderSummary.textContent = label + ": " + t("mediaFolderActive", {
        matched: visibleItems.length, total: state.media.length,
        unknown: mediaLibrary.countUnknownFolders(state.media),
      });
    }
  }

  function openMediaFolderFilter() {
    if (!state.session || !featureSupported(FEATURES.MEDIA_BROWSER) || ui.mediaFolderDialog.open) return;
    const selected = state.mediaFolderFilter || { kind: "all" };
    const folders = mediaLibrary.folderOptions(state.media);
    const choices = [{ kind: "all", label: t("mediaFolderAll") },
      { kind: "unknown", label: t("mediaFolderUnknown") },
      ...folders.map(folder => ({ kind: "folder", id: folder.id, label: folder.label,
        displayLabel: mediaFolderChoiceLabel(folder, folders) }))];
    if (selected.kind === "folder" && !choices.some(choice => choice.id === selected.id)) {
      choices.push({ ...selected, displayLabel: selected.label + " · " + t("mediaFolderMissing") });
    }
    state.mediaFolderDialogSession = state.session;
    state.mediaFolderDraftChoices = choices;
    ui.mediaFolderSelect.replaceChildren();
    choices.forEach((choice, index) => {
      const option = document.createElement("option");
      option.value = String(index);
      option.textContent = choice.displayLabel || choice.label;
      option.selected = choice.kind === selected.kind && (choice.kind !== "folder" || choice.id === selected.id);
      ui.mediaFolderSelect.append(option);
    });
    ui.mediaFolderDialog.showModal();
  }

  function applyMediaFolderFilter() {
    if (!ui.mediaFolderDialog.open || !state.session || state.mediaFolderDialogSession !== state.session) return;
    const value = ui.mediaFolderSelect.value;
    if (!/^(0|[1-9][0-9]*)$/.test(value)) return;
    const choice = state.mediaFolderDraftChoices[Number(value)];
    if (!choice) return;
    state.mediaFolderFilter = choice.kind === "folder"
      ? { kind: "folder", id: choice.id, label: choice.label } : { kind: choice.kind };
    state.mediaPage = 0;
    ui.mediaFolderDialog.close();
    renderMedia();
    if (state.mediaPreviewItem) renderMediaPreviewNavigation();
  }

  function clearMediaFolderFilter() {
    if (!state.session || (ui.mediaFolderDialog.open && state.mediaFolderDialogSession !== state.session)) return;
    state.mediaFolderFilter = { kind: "all" };
    state.mediaPage = 0;
    if (ui.mediaFolderDialog.open) ui.mediaFolderDialog.close();
    renderMedia();
    if (state.mediaPreviewItem) renderMediaPreviewNavigation();
  }

  function renderMediaRatingFilter(visibleItems = displayedMedia()) {
    if (!ui.mediaRatingButton) return;
    const active = Boolean(state.mediaRatingFilter && state.mediaRatingFilter !== "all");
    const sorting = state.mediaSort === "rating-highest" || state.mediaSort === "rating-lowest";
    ui.mediaRatingButton.disabled = !state.session || !featureSupported(FEATURES.MEDIA_BROWSER);
    ui.mediaRatingButton.setAttribute("aria-pressed", String(active));
    ui.mediaRatingClear.hidden = !active;
    ui.mediaRatingSummary.hidden = !active && !sorting;
    if (active || sorting) {
      const labels = { all: "mediaRatingAll", unrated: "mediaRatingUnrated", "1-plus": "mediaRatingOne",
        "2-plus": "mediaRatingTwo", "3-plus": "mediaRatingThree", "4-plus": "mediaRatingFour",
        "5": "mediaRatingFive", unknown: "mediaRatingUnknown" };
      const criterion = t(labels[state.mediaRatingFilter || "all"]);
      const order = sorting ? " · " + t(state.mediaSort === "rating-highest" ? "mediaRatingHighest" : "mediaRatingLowest") : "";
      ui.mediaRatingSummary.textContent = criterion + order + ": " + t("mediaRatingActive", {
        matched: visibleItems.length, total: state.media.length,
        unknown: mediaLibrary.countUnknownRatings(state.media),
      });
    }
  }

  function openMediaRatingFilter() {
    if (!state.session || !featureSupported(FEATURES.MEDIA_BROWSER) || ui.mediaRatingDialog.open) return;
    state.mediaRatingDialogSession = state.session;
    ui.mediaRatingSelect.value = state.mediaRatingFilter || "all";
    ui.mediaRatingDialog.showModal();
  }

  function applyMediaRatingFilter() {
    if (!ui.mediaRatingDialog.open || !state.session || state.mediaRatingDialogSession !== state.session) return;
    const filter = ui.mediaRatingSelect.value;
    if (!["all", "unrated", "1-plus", "2-plus", "3-plus", "4-plus", "5", "unknown"].includes(filter)) return;
    state.mediaRatingFilter = filter;
    state.mediaPage = 0;
    ui.mediaRatingDialog.close();
    renderMedia();
    if (state.mediaPreviewItem) renderMediaPreviewNavigation();
  }

  function clearMediaRatingFilter() {
    if (!state.session || (ui.mediaRatingDialog.open && state.mediaRatingDialogSession !== state.session)) return;
    state.mediaRatingFilter = "all";
    state.mediaPage = 0;
    if (ui.mediaRatingDialog.open) ui.mediaRatingDialog.close();
    renderMedia();
    if (state.mediaPreviewItem) renderMediaPreviewNavigation();
  }

  function openMediaDateFilter() {
    if (!state.session || !featureSupported(FEATURES.MEDIA_BROWSER) || ui.mediaDateDialog.open) return;
    state.mediaDateDialogSession = state.session;
    ui.mediaDateFrom.value = state.mediaDateRange?.start || "";
    ui.mediaDateTo.value = state.mediaDateRange?.end || "";
    ui.mediaDateFrom.removeAttribute("aria-invalid");
    ui.mediaDateTo.removeAttribute("aria-invalid");
    ui.mediaDateError.textContent = "";
    ui.mediaDateError.hidden = true;
    renderMediaDateFilter();
    ui.mediaDateDialog.showModal();
  }

  function applyMediaDateFilter() {
    if (!ui.mediaDateDialog.open || !state.session || state.mediaDateDialogSession !== state.session) return;
    const result = mediaLibrary.validateDateRange({ start: ui.mediaDateFrom.value, end: ui.mediaDateTo.value });
    if (!result.valid) {
      const key = result.error === "required" ? "mediaDateRequired"
        : result.error === "reversed" ? "mediaDateReversed" : "mediaDateInvalid";
      ui.mediaDateError.textContent = t(key);
      ui.mediaDateError.hidden = false;
      ui.mediaDateFrom.setAttribute("aria-invalid", "true");
      ui.mediaDateTo.setAttribute("aria-invalid", "true");
      ui.mediaDateFrom.focus();
      return;
    }
    state.mediaDateRange = result.range;
    state.mediaPage = 0;
    ui.mediaDateDialog.close();
    closeMediaPreview();
    renderMedia();
  }

  function clearMediaDateFilter() {
    if (!state.session || (ui.mediaDateDialog.open && state.mediaDateDialogSession !== state.session)) return;
    state.mediaDateRange = null;
    state.mediaPage = 0;
    if (ui.mediaDateDialog.open) ui.mediaDateDialog.close();
    closeMediaPreview();
    renderMedia();
  }

  function previewableMedia() {
    const source = state.mediaLoaded || state.media.length ? state.media : [];
    const items = mediaLibrary.itemsForDisplay(source, state.mediaFilter, state.mediaSort, resolvedLanguage(), state.mediaDateRange, state.mediaRatingFilter, state.mediaFolderFilter)
      .filter((item) => mediaIsVideo(item)
        ? featureSupported(FEATURES.MEDIA_DOWNLOAD)
        : item.previewAvailable === true && featureSupported(FEATURES.MEDIA_PREVIEW));
    const latest = state.latestMediaItem;
    const latestPreviewable = latest && (mediaIsVideo(latest)
      ? featureSupported(FEATURES.MEDIA_DOWNLOAD)
      : latest.previewAvailable === true && featureSupported(FEATURES.MEDIA_PREVIEW));
    if (state.mediaDateRange || (state.mediaRatingFilter && state.mediaRatingFilter !== "all")
      || (state.mediaFolderFilter && state.mediaFolderFilter.kind !== "all")
      || state.mediaSort === "rating-highest" || state.mediaSort === "rating-lowest") {
      // Capture review may open a file outside the filtered/sorted loaded library.
      // Keep that explicit preview alone instead of prepending it ahead of rating order.
      const opened = state.mediaPreviewItem;
      if (opened && !items.some((item) => item.id === opened.id)) return [opened];
    } else if (latestPreviewable && !items.some((item) => item.id === latest.id)) items.unshift(latest);
    return items;
  }

  function mediaDateGroup(item) {
    if (state.mediaSort === "rating-highest" || state.mediaSort === "rating-lowest") return null;
    if (state.mediaSort === "name") {
      return String(item.name || "#").trim().charAt(0).toLocaleUpperCase(resolvedLanguage()) || "#";
    }
    const parsed = mediaLibrary.mediaDateForItem(item);
    if (!parsed) return t("mediaUnknownDate");
    return new Intl.DateTimeFormat(resolvedLanguage(), {
      dateStyle: "long", ...(!parsed.hasTime ? { timeZone: "UTC" } : {}),
    }).format(parsed.date);
  }

  function formatMediaTime(value) {
    const parsed = mediaLibrary.parseMediaDate(value);
    if (!parsed?.hasTime) return "";
    return new Intl.DateTimeFormat(resolvedLanguage(), { timeStyle: "short" }).format(parsed.date);
  }

  function renderMediaThumbnail(container, item, url = null) {
    container.replaceChildren();
    container.classList.toggle("loading", state.mediaThumbnailLoads.has(item.id));
    if (url) {
      const image = document.createElement("img");
      image.src = url;
      image.alt = t("mediaThumbnail", { name: item.name });
      container.append(image);
    } else {
      const icon = document.createElement("span");
      icon.className = "icon";
      icon.dataset.icon = mediaIsVideo(item) ? "video" : "images";
      container.append(icon);
    }
    if (mediaIsVideo(item)) {
      const play = document.createElement("span");
      play.className = "media-play-badge icon";
      play.dataset.icon = "play";
      container.append(play);
    }
    window.OpenEosIcons?.render(container);
  }

  async function loadMediaThumbnail(item) {
    if (
      !state.session ||
      Boolean(state.mediaBatchOwner) ||
      !featureSupported(FEATURES.MEDIA_THUMBNAIL) ||
      state.mediaThumbnailUrls.has(item.id) ||
      state.mediaThumbnailLoads.has(item.id) ||
      state.mediaThumbnailFailures.has(item.id)
    ) return;
    if (state.mediaAutoImportReading) {
      // The observer may already have unobserved this intersecting element.
      // One flag retains demand without storing an unbounded list of media IDs.
      state.mediaAutoImportThumbnailsDeferred = true;
      return;
    }
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
      if (
        generation !== state.mediaGeneration ||
        !state.media.some((candidate) => candidate.id === item.id) ||
        !Array.from(ui.mediaList.querySelectorAll(".media-thumbnail"))
          .some((candidate) => candidate.dataset.mediaId === item.id)
      ) return;
      const url = URL.createObjectURL(blob);
      const evicted = mediaLibrary.setBounded(
        state.mediaThumbnailUrls,
        item.id,
        url,
        MAX_MEDIA_THUMBNAIL_CACHE_ITEMS,
      );
      evicted.forEach(([evictedId, evictedUrl]) => {
        if (evictedId !== item.id) {
          const evictedItem = state.media.find((candidate) => candidate.id === evictedId);
          if (evictedItem) updateVisibleMediaThumbnail(evictedItem);
        }
        releaseObjectUrl(evictedUrl);
      });
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

  function retryMediaAutoImportThumbnails() {
    if (!state.mediaAutoImportThumbnailsDeferred || !state.session || cameraInteractionBusy()) return;
    state.mediaAutoImportThumbnailsDeferred = false;
    // Re-observe only the currently rendered bounded page. IntersectionObserver
    // decides what is visible; off-page demand never becomes a new request queue.
    Array.from(ui.mediaList.querySelectorAll(".media-thumbnail")).slice(0, MEDIA_PAGE_SIZE)
      .forEach((container) => observeMediaThumbnail(container));
  }

  function updateVisibleMediaThumbnail(item) {
    if (typeof renderMediaAutoImport === "function") renderMediaAutoImport();
    const container = Array.from(ui.mediaList.querySelectorAll(".media-thumbnail"))
      .find((candidate) => candidate.dataset.mediaId === item.id);
    if (container) renderMediaThumbnail(container, item, state.mediaThumbnailUrls.get(item.id));
  }

  function clearMediaThumbnails() {
    state.mediaAutoImportThumbnailsDeferred = false;
    mediaThumbnailObserver?.disconnect();
    ui.mediaList.querySelectorAll("img[src]").forEach((image) => image.removeAttribute("src"));
    state.mediaThumbnailUrls.forEach((url) => releaseObjectUrl(url));
    state.mediaThumbnailUrls.clear();
    state.mediaThumbnailLoads.clear();
    state.mediaThumbnailFailures.clear();
    state.mediaGeneration += 1;
  }

  function resetMediaPreviewTransform() {
    state.mediaPreviewScale = 1;
    state.mediaPreviewOffset = { x: 0, y: 0 };
    state.mediaPreviewDrag = null;
    ui.mediaPreviewImage.style.removeProperty("transform");
    ui.mediaPreviewImage.classList.remove("zoomed", "dragging");
    ui.mediaPreviewResetZoom.hidden = true;
  }

  function renderMediaPreviewTransform() {
    const viewport = {
      width: ui.mediaPreviewImage.clientWidth,
      height: ui.mediaPreviewImage.clientHeight,
    };
    const image = {
      width: ui.mediaPreviewImage.naturalWidth,
      height: ui.mediaPreviewImage.naturalHeight,
    };
    state.mediaPreviewOffset = mediaLibrary.clampImagePan(
      state.mediaPreviewOffset,
      state.mediaPreviewScale,
      viewport,
      image,
    );
    const { x, y } = state.mediaPreviewOffset;
    ui.mediaPreviewImage.style.transform = `translate3d(${x}px, ${y}px, 0) scale(${state.mediaPreviewScale})`;
    ui.mediaPreviewImage.classList.toggle("zoomed", state.mediaPreviewScale > 1);
    ui.mediaPreviewResetZoom.hidden = state.mediaPreviewScale <= 1;
  }

  function setMediaPreviewScale(value) {
    state.mediaPreviewScale = Math.min(6, Math.max(1, Number(value) || 1));
    if (state.mediaPreviewScale <= 1) state.mediaPreviewOffset = { x: 0, y: 0 };
    renderMediaPreviewTransform();
  }

  function clearMediaPreview() {
    state.mediaPreviewGeneration += 1;
    const controller = state.mediaPreviewController;
    state.mediaPreviewController = null;
    controller?.abort();
    state.mediaPreviewItem = null;
    ui.mediaPreviewVideo.pause();
    ui.mediaPreviewVideo.removeAttribute("src");
    ui.mediaPreviewVideo.load();
    ui.mediaPreviewVideo.hidden = true;
    ui.mediaPreviewImage.removeAttribute("src");
    releaseObjectUrl(state.mediaPreviewUrl);
    state.mediaPreviewUrl = null;
    const ticketUrl = state.mediaPreviewTicketUrl;
    state.mediaPreviewTicketUrl = null;
    if (ticketUrl) fetch(ticketUrl, { method: "DELETE", cache: "no-store", keepalive: true }).catch(() => {});
    resetMediaPreviewTransform();
    ui.mediaPreviewImage.alt = "";
    ui.mediaPreviewImage.hidden = true;
    ui.mediaPreviewLoading.hidden = false;
    ui.mediaPreviewUnavailable.hidden = true;
    ui.mediaPreviewRetry.hidden = true;
    ui.mediaPreviewFailureDownload.hidden = true;
    ui.mediaPreviewDownload.hidden = true;
    ui.mediaPreviewDetails.hidden = true;
    ui.mediaPreviewTitle.textContent = "";
    ui.mediaPreviewMeta.textContent = "";
    ui.mediaPreviewKind.textContent = "";
    renderMediaPreviewNavigation();
  }

  function failMediaVideoPreview() {
    if (!state.mediaPreviewItem || !mediaIsVideo(state.mediaPreviewItem)) return;
    const errorCode = ui.mediaPreviewVideo.error?.code;
    ui.mediaPreviewVideo.pause();
    ui.mediaPreviewVideo.removeAttribute("src");
    ui.mediaPreviewVideo.load();
    ui.mediaPreviewVideo.hidden = true;
    const ticketUrl = state.mediaPreviewTicketUrl;
    state.mediaPreviewTicketUrl = null;
    if (ticketUrl) fetch(ticketUrl, { method: "DELETE", cache: "no-store", keepalive: true }).catch(() => {});
    ui.mediaPreviewLoading.hidden = true;
    const failure = mediaLibrary.videoPlaybackFailure(errorCode);
    const container = mediaLibrary.videoContainerLabel(state.mediaPreviewItem.name);
    ui.mediaPreviewUnavailableCopy.textContent = t(
      failure === "codec" ? "videoPlaybackCodecUnsupported" : "videoPlaybackTransportUnavailable",
      { container },
    );
    ui.mediaPreviewRetry.hidden = failure === "codec";
    ui.mediaPreviewFailureDownload.hidden = !featureSupported(FEATURES.MEDIA_DOWNLOAD);
    ui.mediaPreviewUnavailable.hidden = false;
    renderMediaPreviewNavigation();
  }

  function mediaMetadataSupported() {
    return featureSupported(FEATURES.MEDIA_PROTECT) || featureSupported(FEATURES.MEDIA_RATING) ||
      featureSupported(FEATURES.MEDIA_ROTATE) || featureSupported(FEATURES.MEDIA_ARCHIVE);
  }

  function mediaManagementSupported() {
    return featureSupported(FEATURES.MEDIA_BROWSER) || mediaMetadataSupported() ||
      featureSupported(FEATURES.MEDIA_DELETE);
  }

  function formatMediaDimensions(item) {
    const width = Number(item?.widthPixels);
    const height = Number(item?.heightPixels);
    if (!Number.isSafeInteger(width) || width <= 0 || !Number.isSafeInteger(height) || height <= 0) return "";
    return `${width} x ${height}`;
  }

  function formatMediaContentType(value) {
    const contentType = String(value || "").split(";", 1)[0].trim();
    return contentType && contentType !== "application/octet-stream" ? contentType : "";
  }

  function replaceMediaItem(item) {
    const index = state.media.findIndex((candidate) => candidate.id === item.id);
    if (index >= 0) state.media[index] = { ...state.media[index], ...item };
    if (state.mediaDetailsItem?.id === item.id) {
      state.mediaDetailsItem = { ...state.mediaDetailsItem, ...item };
    }
  }

  function renderMediaDetails() {
    if (typeof renderMediaAutoImport === "function") renderMediaAutoImport();
    if (!ui.mediaDetailsDialog?.open || !state.mediaDetailsItem) return;
    const item = state.mediaDetailsItem;
    const disabled = state.mediaDetailsBusy || cameraInteractionBusy();
    ui.mediaDetailsName.textContent = item.name;
    ui.mediaDetailsKind.textContent = String(item.kind || "").toUpperCase();
    ui.mediaDetailsSummary.textContent = [
      formatDate(item.captureTime),
      formatMediaDimensions(item),
      formatMediaSize(item),
      formatMediaContentType(item.contentType),
    ]
      .filter(Boolean).join(" · ");
    ui.mediaDetailsLoading.hidden = !state.mediaDetailsBusy;

    const protectionSupported = featureSupported(FEATURES.MEDIA_PROTECT);
    ui.mediaProtectionSection.hidden = !protectionSupported;
    ui.mediaProtectButton.disabled = disabled;
    ui.mediaUnprotectButton.disabled = disabled;
    ui.mediaProtectButton.classList.toggle("active", item.protected === true);
    ui.mediaUnprotectButton.classList.toggle("active", item.protected === false);
    ui.mediaProtectButton.setAttribute("aria-pressed", String(item.protected === true));
    ui.mediaUnprotectButton.setAttribute("aria-pressed", String(item.protected === false));

    const ratingSupported = featureSupported(FEATURES.MEDIA_RATING);
    ui.mediaRatingSection.hidden = !ratingSupported;
    ui.mediaRatingControl.querySelectorAll("[data-media-rating]").forEach((button) => {
      const value = Number(button.dataset.mediaRating);
      button.disabled = disabled;
      button.classList.toggle("active", item.rating === value);
      button.setAttribute("aria-pressed", String(item.rating === value));
      button.setAttribute("aria-label", value === 0 ? t("ratingOff") : t("ratingValue", { value }));
    });

    const rotationSupported = featureSupported(FEATURES.MEDIA_ROTATE);
    ui.mediaRotationSection.hidden = !rotationSupported;
    ui.mediaRotationControl.querySelectorAll("[data-media-rotation]").forEach((button) => {
      const value = Number(button.dataset.mediaRotation);
      button.disabled = disabled;
      button.classList.toggle("active", item.rotationDegrees === value);
      button.setAttribute("aria-pressed", String(item.rotationDegrees === value));
      button.setAttribute("aria-label", t("rotationValue", { value }));
    });

    const archiveSupported = featureSupported(FEATURES.MEDIA_ARCHIVE) && typeof item.archived === "boolean";
    ui.mediaArchiveSection.hidden = !archiveSupported;
    ui.mediaArchiveButton.disabled = disabled;
    ui.mediaUnarchiveButton.disabled = disabled;
    ui.mediaArchiveButton.classList.toggle("active", item.archived === true);
    ui.mediaUnarchiveButton.classList.toggle("active", item.archived === false);
    ui.mediaArchiveButton.setAttribute("aria-pressed", String(item.archived === true));
    ui.mediaUnarchiveButton.setAttribute("aria-pressed", String(item.archived === false));

    ui.mediaDetailsDownload.hidden = !featureSupported(FEATURES.MEDIA_DOWNLOAD);
    ui.mediaDetailsDownload.disabled = disabled || !featureSupported(FEATURES.MEDIA_DOWNLOAD);
    ui.mediaDetailsDelete.hidden = !featureSupported(FEATURES.MEDIA_DELETE);
    ui.mediaDetailsDelete.disabled = disabled || !featureSupported(FEATURES.MEDIA_DELETE);
  }

  function clearMediaDetails() {
    state.mediaDetailsGeneration += 1;
    state.mediaDetailsItem = null;
    state.mediaDetailsBusy = false;
    if (typeof renderMediaAutoImport === "function") renderMediaAutoImport();
  }

  function closeMediaDetails() {
    if (ui.mediaDetailsDialog?.open) ui.mediaDetailsDialog.close();
    clearMediaDetails();
  }

  async function openMediaDetails(item) {
    if (!state.session || cameraInteractionBusy()) return;
    clearMediaDetails();
    const generation = state.mediaDetailsGeneration;
    state.mediaDetailsItem = item;
    ui.mediaDetailsDialog.showModal();
    renderMediaDetails();
    if (!mediaMetadataSupported()) return;
    state.mediaDetailsBusy = true;
    renderMediaDetails();
    try {
      const updated = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}/info`,
      );
      if (generation !== state.mediaDetailsGeneration || state.mediaDetailsItem?.id !== item.id) return;
      replaceMediaItem(updated);
      renderMedia();
    } catch (error) {
      if (generation === state.mediaDetailsGeneration) {
        const normalized = captureError(error);
        showToast(normalized.message, true);
      }
    } finally {
      if (generation === state.mediaDetailsGeneration) {
        state.mediaDetailsBusy = false;
        renderMediaDetails();
      }
    }
  }

  async function updateMediaMetadata(feature, suffix, json) {
    const item = state.mediaDetailsItem;
    if (!state.session || !item || !featureSupported(feature) || state.mediaDetailsBusy || cameraInteractionBusy()) return;
    const generation = state.mediaDetailsGeneration;
    state.mediaDetailsBusy = true;
    renderMediaDetails();
    try {
      const updated = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}/${suffix}`,
        { method: "PUT", json },
      );
      if (generation !== state.mediaDetailsGeneration || state.mediaDetailsItem?.id !== item.id) return;
      replaceMediaItem(updated);
      renderMedia();
      showToast(t("mediaMetadataUpdated", { name: item.name }));
    } catch (error) {
      if (generation === state.mediaDetailsGeneration) {
        const normalized = captureError(error);
        showToast(normalized.message, true);
      }
    } finally {
      if (generation === state.mediaDetailsGeneration) {
        state.mediaDetailsBusy = false;
        renderMediaDetails();
      }
    }
  }

  function closeMediaPreview() {
    if (ui.mediaPreviewDialog.open) ui.mediaPreviewDialog.close();
    clearMediaPreview();
  }

  async function openMediaPreview(item) {
    const video = mediaIsVideo(item);
    if (
      !state.session ||
      Boolean(state.mediaAutoImportReading) ||
      Boolean(state.mediaBatchOwner) ||
      (video && !featureSupported(FEATURES.MEDIA_DOWNLOAD)) ||
      (!video && (item.previewAvailable !== true || !featureSupported(FEATURES.MEDIA_PREVIEW)))
    ) return;
    clearMediaPreview();
    const generation = state.mediaPreviewGeneration;
    // Playback allocation must return its ticket so stale responses can revoke it.
    const controller = video ? null : new AbortController();
    state.mediaPreviewController = controller;
    state.mediaPreviewItem = item;
    if (!ui.mediaPreviewDialog.open) ui.mediaPreviewDialog.showModal();
    renderMediaPreviewNavigation();
    try {
      if (video) {
        const ticket = await api(
          `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}/playback`,
          { method: "POST" },
        );
        if (generation !== state.mediaPreviewGeneration || state.mediaPreviewItem?.id !== item.id) {
          fetch(ticket.url, { method: "DELETE", cache: "no-store", keepalive: true }).catch(() => {});
          return;
        }
        state.mediaPreviewTicketUrl = ticket.url;
        ui.mediaPreviewVideo.src = ticket.url;
        ui.mediaPreviewVideo.hidden = false;
        ui.mediaPreviewVideo.load();
        ui.mediaPreviewLoading.hidden = true;
        return;
      }
      const blob = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}/preview`,
        { responseType: "blob", signal: controller.signal },
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
      resetMediaPreviewTransform();
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
      const container = mediaLibrary.videoContainerLabel(item.name);
      ui.mediaPreviewUnavailableCopy.textContent = t(
        video && normalized.code === "MEDIA_PLAYBACK_STORAGE_UNAVAILABLE"
          ? "videoPlaybackStorageUnavailable"
          : video
            ? "videoPlaybackTransportUnavailable"
            : "previewUnavailable",
        { container },
      );
      ui.mediaPreviewRetry.hidden = !video;
      ui.mediaPreviewFailureDownload.hidden = !video || !featureSupported(FEATURES.MEDIA_DOWNLOAD);
      ui.mediaPreviewUnavailable.hidden = false;
      renderMediaPreviewNavigation();
      showToast(normalized.message, true);
    } finally {
      if (state.mediaPreviewController === controller) state.mediaPreviewController = null;
    }
  }

  function renderMediaPreviewNavigation() {
    if (typeof renderMediaAutoImport === "function") renderMediaAutoImport();
    const items = previewableMedia();
    const index = items.findIndex((item) => item.id === state.mediaPreviewItem?.id);
    ui.mediaPreviewPrevious.disabled = Boolean(state.mediaBatchOwner || state.mediaAutoImportReading) || index <= 0;
    ui.mediaPreviewNext.disabled = Boolean(state.mediaBatchOwner || state.mediaAutoImportReading) || index < 0 || index >= items.length - 1;
    ui.mediaPreviewRetry.disabled = Boolean(state.mediaBatchOwner || state.mediaAutoImportReading);
    ui.mediaPreviewFailureDownload.disabled = cameraInteractionBusy();
    const item = state.mediaPreviewItem;
    if (!item) return;
    ui.mediaPreviewTitle.textContent = item.name;
    ui.mediaPreviewKind.textContent = String(item.kind || "").toUpperCase();
    const summary = [
      t("mediaPreviewPosition", { position: Math.max(0, index + 1), total: items.length }),
      formatMediaDimensions(item),
      formatMediaSize(item),
    ].filter((value) => value && value !== "-");
    ui.mediaPreviewMeta.textContent = summary.join(" · ");
    ui.mediaPreviewDownload.hidden = !featureSupported(FEATURES.MEDIA_DOWNLOAD);
    ui.mediaPreviewDownload.disabled = cameraInteractionBusy();
    ui.mediaPreviewDetails.hidden = !mediaManagementSupported();
    ui.mediaPreviewDetails.disabled = cameraInteractionBusy();
  }

  function openAdjacentMedia(offset) {
    const items = previewableMedia();
    const index = items.findIndex((item) => item.id === state.mediaPreviewItem?.id);
    const item = items[index + offset];
    if (item) void openMediaPreview(item);
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
    renderMediaSelection();
    renderMediaBatch();
    if (typeof renderMediaAutoImport === "function") renderMediaAutoImport();
    const transfer = state.mediaUpload || state.mediaDownload;
    const uploading = Boolean(state.mediaUpload);
    ui.mediaTransfer.hidden = !transfer;
    ui.mediaRefreshButton.disabled = !state.session || cameraInteractionBusy();
    if (!transfer) return;
    ui.mediaTransferName.textContent = transfer.name;
    if (transfer.cancelling) {
      ui.mediaTransferStatus.textContent = t(uploading ? "cancellingUpload" : "cancellingDownload");
    } else {
      const transferred = formatBytes(transfer.bytesTransferred);
      const total = transfer.totalBytes;
      ui.mediaTransferStatus.textContent = total
        ? t(uploading ? "uploadProgress" : "downloadProgress", {
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
    ui.mediaTransferCancel.setAttribute("aria-label", t(uploading ? "cancelUpload" : "cancelDownload"));
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
    const session = state.session;
    const sessionId = session.id;
    const owner = { cancelled: false, silent: false };
    state.mediaDownloadOwner = owner;
    const ownsDownload = () => state.mediaDownloadOwner === owner && state.session === session;
    state.mediaDownloadPreparing = true;
    setOperationState(t("preparingDownload"));
    renderMedia();
    renderAvailability();
    renderMediaPreviewNavigation();
    let writable = null;
    let writableClosed = false;
    let transfer = null;
    try {
      const destination = await chooseMediaWritable(item);
      writable = destination.writable;
      // Disconnect can finish while the browser is still creating the destination.
      // An old handle must be discarded before any request can use another session.
      if (!ownsDownload() || owner.cancelled) throw mediaTransfer.cancellationError();
      if (destination.cancelled) {
        setOperationState(t("ready"));
        return;
      }
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
      renderMediaPreviewNavigation();
      const response = await api(
        `/v1/session/${encodeURIComponent(sessionId)}/media/${encodeURIComponent(item.id)}`,
        { responseType: "response", signal: controller.signal },
      );
      if (!ownsDownload() || owner.cancelled) throw mediaTransfer.cancellationError();
      const result = await mediaTransfer.readResponse(response, {
        signal: controller.signal,
        expectedBytes: item.sizeBytes,
        writeChunk: writable ? (chunk) => writable.write(chunk) : null,
        onProgress: (progress) => {
          if (!ownsDownload() || owner.cancelled || state.mediaDownload !== transfer) return;
          transfer.bytesTransferred = progress.bytesTransferred;
          transfer.totalBytes = progress.totalBytes;
          scheduleMediaTransferRender();
        },
      });
      if (controller.signal.aborted || owner.cancelled || !ownsDownload()) throw mediaTransfer.cancellationError();
      if (writable) {
        await writable.close();
        writableClosed = true;
      } else if (result.blob) {
        saveMediaBlob(result.blob, item.name);
      } else {
        throw new Error("Media download completed without a file destination.");
      }
      if (!ownsDownload()) return;
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
      if (!ownsDownload()) return;
      if (owner.cancelled || mediaTransfer.isAbortError(error) || transfer?.controller.signal.aborted) {
        if (!(transfer?.silent ?? owner.silent)) {
          showToast(t("downloadCancelled"));
          setOperationState(t("ready"));
        }
      } else {
        const normalized = captureError(error);
        setOperationState(normalized.message, true);
        showToast(normalized.message, true);
      }
    } finally {
      if (ownsDownload()) {
        clearScheduledMediaTransferRender();
        state.mediaDownloadPreparing = false;
        if (state.mediaDownload === transfer) state.mediaDownload = null;
        state.mediaDownloadOwner = null;
        renderMedia();
        renderAvailability();
        renderMediaPreviewNavigation();
      }
    }
  }

  function cancelMediaDownload({ silent = false } = {}) {
    cancelMediaBatch({ silent });
    if (state.mediaDownloadOwner) {
      state.mediaDownloadOwner.cancelled = true;
      state.mediaDownloadOwner.silent = silent;
    }
    const transfer = state.mediaDownload;
    if (!transfer || transfer.controller.signal.aborted) return;
    transfer.silent = silent;
    transfer.cancelling = true;
    transfer.controller.abort();
    renderMediaTransfer();
  }

  function uploadMedia(file) {
    if (!state.session || !featureSupported(FEATURES.MEDIA_UPLOAD) || cameraInteractionBusy()) return;
    const xhr = new XMLHttpRequest();
    const transfer = {
      name: file.name,
      bytesTransferred: 0,
      totalBytes: file.size,
      xhr,
      cancelling: false,
      silent: false,
    };
    state.mediaUpload = transfer;
    setOperationState(t("uploading", { name: file.name }));
    renderMediaTransfer();
    renderAvailability();
    xhr.open(
      "POST",
      `/v1/session/${encodeURIComponent(state.session.id)}/media?filename=${encodeURIComponent(file.name)}`,
    );
    if (state.token) xhr.setRequestHeader("Authorization", `Bearer ${state.token}`);
    xhr.setRequestHeader("Content-Type", file.type || "application/octet-stream");
    xhr.upload.onprogress = (event) => {
      if (state.mediaUpload !== transfer) return;
      transfer.bytesTransferred = event.loaded;
      transfer.totalBytes = event.lengthComputable ? event.total : file.size;
      scheduleMediaTransferRender();
    };
    xhr.onload = async () => {
      if (state.mediaUpload !== transfer) return;
      state.mediaUpload = null;
      clearScheduledMediaTransferRender();
      renderMediaTransfer();
      renderAvailability();
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          await refreshMedia();
          showToast(t("uploaded", { name: file.name }));
          setOperationState(t("ready"));
        } catch (error) {
          const normalized = captureError(error);
          showToast(normalized.message, true);
          setOperationState(normalized.message, true);
        }
      } else {
        let message = t("operationFailed");
        try {
          const payload = JSON.parse(xhr.responseText || "{}");
          message = payload.error?.message || message;
        } catch (_) {
          // Keep the readable fallback for non-JSON bridge errors.
        }
        showToast(message, true);
        setOperationState(message, true);
      }
      clearScheduledMediaTransferRender();
      renderMedia();
      renderAvailability();
    };
    xhr.onerror = () => {
      if (state.mediaUpload !== transfer) return;
      const message = t("operationFailed");
      showToast(message, true);
      setOperationState(message, true);
      state.mediaUpload = null;
      renderMediaTransfer();
      renderAvailability();
    };
    xhr.onabort = async () => {
      if (state.mediaUpload !== transfer) return;
      state.mediaUpload = null;
      renderMediaTransfer();
      renderAvailability();
      if (!transfer.silent) {
        const refreshed = await refreshMedia();
        const committed = refreshed && state.media.some((item) =>
          item.name?.localeCompare(file.name, undefined, { sensitivity: "accent" }) === 0 &&
          Number(item.sizeBytes) === file.size
        );
        showToast(t(committed ? "uploaded" : "uploadCancelled", { name: file.name }));
        setOperationState(t("ready"));
      }
    };
    xhr.send(file);
  }

  function cancelMediaUpload({ silent = false } = {}) {
    const transfer = state.mediaUpload;
    if (!transfer || transfer.cancelling) return;
    transfer.silent = silent;
    transfer.cancelling = true;
    renderMediaTransfer();
    transfer.xhr.abort();
  }

  function cancelMediaTransfer() {
    if (state.mediaUpload) cancelMediaUpload();
    else cancelMediaDownload();
  }

  async function deleteMedia(item, button) {
    const session = state.session;
    if (!session || !featureSupported(FEATURES.MEDIA_DELETE) || cameraInteractionBusy()) return;
    if (!window.confirm(t("deleteConfirm", { name: item.name }))) return;
    button.disabled = true;
    try {
      await api(
        `/v1/session/${encodeURIComponent(session.id)}/media/${encodeURIComponent(item.id)}`,
        { method: "DELETE" },
      );
      // A replacement connection can reuse both the session ID and the media ID.
      if (state.session !== session) return;
      state.media = state.media.filter((candidate) => candidate.id !== item.id);
      state.mediaSelection?.remove(item.id);
      const thumbnailUrl = state.mediaThumbnailUrls.get(item.id);
      state.mediaThumbnailUrls.delete(item.id);
      state.mediaThumbnailLoads.delete(item.id);
      state.mediaThumbnailFailures.delete(item.id);
      if (state.mediaPreviewItem?.id === item.id) closeMediaPreview();
      if (state.mediaDetailsItem?.id === item.id) closeMediaDetails();
      renderMedia();
      releaseObjectUrl(thumbnailUrl);
      showToast(t("deleted", { name: item.name }));
    } catch (error) {
      if (state.session !== session) return;
      const normalized = captureError(error);
      showToast(normalized.message, true);
      button.disabled = false;
    }
  }

  function diagnosticCapabilities() {
    const capabilities = state.capabilities;
    if (!capabilities) return null;
    return {
      profile: capabilities.profile || null,
      supported: capabilities.supported || [],
      planned: capabilities.planned || [],
      reasons: capabilities.reasons || {},
      liveView: capabilities.liveView || null,
      settings: (capabilities.settings || []).map((setting) => ({
        key: setting.key,
        label: setting.label,
        inputKind: setting.inputKind || "choice",
        maxLength: setting.maxLength ?? null,
        choiceCount: Array.isArray(setting.values) ? setting.values.length : 0,
      })),
      fileNaming: capabilities.fileNaming ? {
        available: true,
        stillFilenameModeOptions: capabilities.fileNaming.stillFilenameModeOptions || [],
        movieReelRange: capabilities.fileNaming.movieReelRange || null,
        movieClipRange: capabilities.fileNaming.movieClipRange || null,
      } : null,
      evidence: capabilities.evidence || null,
    };
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
      capabilities: diagnosticCapabilities(),
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
      mediaLibrary: {
        itemCount: state.media.length,
        loadStatus: state.mediaLoadStatus,
        scope: state.mediaScope,
        hasMore: state.mediaHasMore,
        requestLimit: state.mediaScope === "recent" ? 61 : null,
      },
      mediaTransfer: (state.mediaUpload || state.mediaDownload) ? {
        active: true,
        kind: state.mediaUpload ? "upload" : "download",
        bytesTransferred: (state.mediaUpload || state.mediaDownload).bytesTransferred,
        totalBytes: (state.mediaUpload || state.mediaDownload).totalBytes,
        destination: state.mediaDownload?.destination || null,
        cancelling: (state.mediaUpload || state.mediaDownload).cancelling,
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

  function formatMediaSize(item) {
    // Media metadata uses zero for unavailable size as well as empty files.
    // Only positive numeric evidence can support a size label here.
    const bytes = item?.sizeBytes;
    return typeof bytes === "number" && Number.isFinite(bytes) && bytes > 0 ? formatBytes(bytes) : "";
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
    const parsed = mediaLibrary.parseMediaDate(value);
    if (!parsed) return "";
    return new Intl.DateTimeFormat(resolvedLanguage(), {
      dateStyle: "medium", ...(parsed.hasTime ? { timeStyle: "short" } : { timeZone: "UTC" }),
    }).format(parsed.date);
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
    ui.ccapiUrlInput.addEventListener("input", () => {
      clearConnectionError();
      renderConnectionGuidance();
      renderAvailability();
    });
    ui.tokenInput.addEventListener("input", () => {
      cancelCameraScan();
      clearConnectionError();
      state.cameras = [];
      state.scanState = "idle";
      renderCameras();
      renderConnectionGuidance();
    });
    ui.scanCancelButton.addEventListener("click", cancelCameraScan);
    ui.healthRetryButton.addEventListener("click", refreshHealth);
    ui.connectionFixButton.addEventListener("click", correctConnection);
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
    ui.shutterDisconnectConfirm.addEventListener("click", () => {
      state.shutterDisconnectWarning = false;
      renderAvailability();
    });
    document.querySelectorAll(".tab").forEach((button) => {
      button.addEventListener("click", () => selectView(button.dataset.view));
    });
    ui.photoModeButton.addEventListener("click", () => selectCaptureMode("photo"));
    ui.videoModeButton.addEventListener("click", () => selectCaptureMode("video"));
    ui.shutterAFToggle.addEventListener("change", () => {
      if (canChangeShutterAF() && (ui.shutterAFToggle.checked || shutterAFSupported())) {
        state.shutterAutofocus = ui.shutterAFToggle.checked;
      }
      renderAvailability();
    });
    ui.shutterButton.addEventListener("click", operateShutter);
    ui.latestMediaButton.addEventListener("click", openLatestMedia);
    ui.latestMediaRetry.addEventListener("click", retryLatestMedia);
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
    ui.mediaFolderButton.addEventListener("click", openMediaFolderFilter);
    ui.mediaFolderClear.addEventListener("click", clearMediaFolderFilter);
    ui.mediaFolderDialogClear.addEventListener("click", clearMediaFolderFilter);
    ui.mediaFolderClose.addEventListener("click", () => ui.mediaFolderDialog.close());
    ui.mediaFolderCancel.addEventListener("click", () => ui.mediaFolderDialog.close());
    ui.mediaFolderDialog.addEventListener("close", () => {
      state.mediaFolderDialogSession = null;
      state.mediaFolderDraftChoices = [];
    });
    ui.mediaFolderForm.addEventListener("submit", (event) => {
      event.preventDefault(); applyMediaFolderFilter();
    });
    ui.mediaRatingButton.addEventListener("click", openMediaRatingFilter);
    ui.mediaRatingClear.addEventListener("click", clearMediaRatingFilter);
    ui.mediaRatingDialogClear.addEventListener("click", clearMediaRatingFilter);
    ui.mediaRatingClose.addEventListener("click", () => ui.mediaRatingDialog.close());
    ui.mediaRatingCancel.addEventListener("click", () => ui.mediaRatingDialog.close());
    ui.mediaRatingDialog.addEventListener("close", () => { state.mediaRatingDialogSession = null; });
    ui.mediaRatingForm.addEventListener("submit", (event) => {
      event.preventDefault();
      applyMediaRatingFilter();
    });
    ui.mediaDateButton.addEventListener("click", openMediaDateFilter);
    ui.mediaDateClear.addEventListener("click", clearMediaDateFilter);
    ui.mediaDateDialogClear.addEventListener("click", clearMediaDateFilter);
    ui.mediaDateClose.addEventListener("click", () => ui.mediaDateDialog.close());
    ui.mediaDateCancel.addEventListener("click", () => ui.mediaDateDialog.close());
    ui.mediaDateDialog.addEventListener("close", () => { state.mediaDateDialogSession = null; });
    ui.mediaDateForm.addEventListener("submit", (event) => {
      event.preventDefault();
      applyMediaDateFilter();
    });
    ui.mediaRefreshButton.addEventListener("click", refreshMedia);
    ui.mediaSelectFiltered.addEventListener("click", () => {
      if (cameraInteractionBusy()) return;
      state.mediaSelection.replaceLoaded(state.media);
      state.mediaSelection.setMany(displayedMedia().map((item) => item.id), true);
      renderMedia();
    });
    ui.mediaSelectionClear.addEventListener("click", () => {
      if (cameraInteractionBusy()) return;
      state.mediaSelection.clear();
      renderMedia();
    });
    document.querySelectorAll("[data-open-media-history]").forEach((button) => button.addEventListener("click", openMediaHistory));
    ui.mediaHistoryClose.addEventListener("click", () => ui.mediaHistoryDialog.close());
    ui.mediaHistoryDialog.addEventListener("close", () => { mediaHistoryViewGeneration += 1; mediaHistoryClearOwner = null; });
    ui.mediaHistoryClear.addEventListener("click", () => {
      ui.mediaHistoryConfirmYes.disabled = false;
      ui.mediaHistoryConfirm.hidden = false;
      ui.mediaHistoryConfirmYes.focus();
    });
    ui.mediaHistoryConfirmNo.addEventListener("click", () => {
      ui.mediaHistoryConfirm.hidden = true;
      ui.mediaHistoryClear.focus();
    });
    ui.mediaHistoryConfirmYes.addEventListener("click", clearMediaHistory);
    ui.mediaBatchStart.addEventListener("click", downloadSelectedMedia);
    ui.mediaBatchCancel.addEventListener("click", () => cancelMediaBatch());
    ui.mediaAutoImportStart?.addEventListener("click", startMediaAutoImport);
    ui.mediaAutoImportStop?.addEventListener("click", () => stopMediaAutoImport());
    document.addEventListener?.("visibilitychange", () => {
      if (document.visibilityState !== "visible") stopMediaAutoImport("backgrounded");
      else {
        renderAvailability();
        renderMediaAutoImport();
      }
    });
    window.addEventListener("pagehide", () => stopMediaAutoImport("backgrounded"));
    window.addEventListener("pageshow", () => {
      renderAvailability();
      renderMediaAutoImport();
    });
    ui.mediaScopeControl.addEventListener("click", (event) => {
      const button = event.target.closest?.("[data-media-scope]");
      if (!button || button.dataset.mediaScope === state.mediaScope) return;
      const scope = button.dataset.mediaScope;
      state.mediaScope = scope;
      state.mediaPage = 0;
      closeMediaPreview();
      if (scope === "recent" && state.mediaLoadStatus === "COMPLETE") {
        state.mediaHasMore = state.mediaHasMore || state.media.length > 60;
        state.media = state.media.slice(0, 60);
        renderMedia();
      } else {
        state.mediaLoaded = false;
        state.mediaHasMore = false;
        state.mediaLoadStatus = "NOT_LOADED";
        renderMedia();
        refreshMedia();
      }
    });
    ui.mediaFilterControl.addEventListener("click", (event) => {
      const button = event.target.closest?.("[data-media-filter]");
      if (!button) return;
      state.mediaFilter = button.dataset.mediaFilter;
      state.mediaPage = 0;
      closeMediaPreview();
      renderMedia();
    });
    ui.mediaSortSelect.addEventListener("change", () => {
      state.mediaSort = ui.mediaSortSelect.value;
      state.mediaPage = 0;
      closeMediaPreview();
      renderMedia();
    });
    ui.mediaPagePrevious.addEventListener("click", () => {
      state.mediaPage = Math.max(0, state.mediaPage - 1);
      closeMediaPreview();
      renderMedia();
      ui.mediaPagination.scrollIntoView({ block: "start" });
    });
    ui.mediaPageNext.addEventListener("click", () => {
      state.mediaPage += 1;
      closeMediaPreview();
      renderMedia();
      ui.mediaPagination.scrollIntoView({ block: "start" });
    });
    ui.mediaUploadButton.addEventListener("click", () => ui.mediaUploadInput.click());
    ui.mediaUploadInput.addEventListener("change", () => {
      const [file] = ui.mediaUploadInput.files || [];
      ui.mediaUploadInput.value = "";
      if (file) uploadMedia(file);
    });
    ui.mediaTransferCancel.addEventListener("click", cancelMediaTransfer);
    ui.mediaPreviewClose.addEventListener("click", closeMediaPreview);
    ui.mediaPreviewPrevious.addEventListener("click", () => openAdjacentMedia(-1));
    ui.mediaPreviewNext.addEventListener("click", () => openAdjacentMedia(1));
    ui.mediaPreviewVideo.addEventListener("error", () => {
      failMediaVideoPreview();
    });
    ui.mediaPreviewRetry.addEventListener("click", () => {
      const item = state.mediaPreviewItem;
      if (item) void openMediaPreview(item);
    });
    ui.mediaPreviewFailureDownload.addEventListener("click", () => {
      if (state.mediaPreviewItem) void downloadMedia(state.mediaPreviewItem);
    });
    ui.mediaPreviewDownload.addEventListener("click", () => {
      if (state.mediaPreviewItem) void downloadMedia(state.mediaPreviewItem);
    });
    ui.mediaPreviewDetails.addEventListener("click", () => {
      const item = state.mediaPreviewItem;
      if (!item) return;
      closeMediaPreview();
      void openMediaDetails(item);
    });
    ui.mediaPreviewResetZoom.addEventListener("click", resetMediaPreviewTransform);
    ui.mediaPreviewImage.addEventListener("dblclick", () => {
      setMediaPreviewScale(state.mediaPreviewScale > 1 ? 1 : 2.5);
    });
    ui.mediaPreviewImage.addEventListener("wheel", (event) => {
      if (ui.mediaPreviewImage.hidden) return;
      event.preventDefault();
      setMediaPreviewScale(state.mediaPreviewScale * (event.deltaY < 0 ? 1.2 : 1 / 1.2));
    }, { passive: false });
    ui.mediaPreviewImage.addEventListener("pointerdown", (event) => {
      if (state.mediaPreviewScale <= 1 || event.button !== 0) return;
      state.mediaPreviewDrag = {
        pointerId: event.pointerId,
        x: event.clientX,
        y: event.clientY,
        offset: { ...state.mediaPreviewOffset },
      };
      ui.mediaPreviewImage.setPointerCapture(event.pointerId);
      ui.mediaPreviewImage.classList.add("dragging");
    });
    ui.mediaPreviewImage.addEventListener("pointermove", (event) => {
      const drag = state.mediaPreviewDrag;
      if (!drag || drag.pointerId !== event.pointerId) return;
      event.preventDefault();
      state.mediaPreviewOffset = {
        x: drag.offset.x + event.clientX - drag.x,
        y: drag.offset.y + event.clientY - drag.y,
      };
      renderMediaPreviewTransform();
    });
    const finishMediaPreviewDrag = (event) => {
      if (state.mediaPreviewDrag?.pointerId !== event.pointerId) return;
      if (ui.mediaPreviewImage.hasPointerCapture(event.pointerId)) {
        ui.mediaPreviewImage.releasePointerCapture(event.pointerId);
      }
      state.mediaPreviewDrag = null;
      ui.mediaPreviewImage.classList.remove("dragging");
    };
    ui.mediaPreviewImage.addEventListener("pointerup", finishMediaPreviewDrag);
    ui.mediaPreviewImage.addEventListener("pointercancel", finishMediaPreviewDrag);
    ui.mediaPreviewDialog.addEventListener("close", clearMediaPreview);
    ui.mediaPreviewDialog.addEventListener("click", (event) => {
      if (event.target === ui.mediaPreviewDialog) closeMediaPreview();
    });
    ui.mediaDetailsClose.addEventListener("click", closeMediaDetails);
    ui.mediaDetailsDialog.addEventListener("close", clearMediaDetails);
    ui.mediaDetailsDialog.addEventListener("click", (event) => {
      if (event.target === ui.mediaDetailsDialog) closeMediaDetails();
    });
    ui.mediaProtectButton.addEventListener("click", () => {
      void updateMediaMetadata(FEATURES.MEDIA_PROTECT, "protection", { enabled: true });
    });
    ui.mediaUnprotectButton.addEventListener("click", () => {
      void updateMediaMetadata(FEATURES.MEDIA_PROTECT, "protection", { enabled: false });
    });
    ui.mediaRatingControl.addEventListener("click", (event) => {
      const button = event.target.closest?.("[data-media-rating]");
      if (button) void updateMediaMetadata(FEATURES.MEDIA_RATING, "rating", { value: Number(button.dataset.mediaRating) });
    });
    ui.mediaRotationControl.addEventListener("click", (event) => {
      const button = event.target.closest?.("[data-media-rotation]");
      if (button) void updateMediaMetadata(FEATURES.MEDIA_ROTATE, "rotation", { degrees: Number(button.dataset.mediaRotation) });
    });
    ui.mediaArchiveButton.addEventListener("click", () => {
      void updateMediaMetadata(FEATURES.MEDIA_ARCHIVE, "archive", { enabled: true });
    });
    ui.mediaUnarchiveButton.addEventListener("click", () => {
      void updateMediaMetadata(FEATURES.MEDIA_ARCHIVE, "archive", { enabled: false });
    });
    ui.mediaDetailsDownload.addEventListener("click", () => {
      if (state.mediaDetailsItem) void downloadMedia(state.mediaDetailsItem);
    });
    ui.mediaDetailsDelete.addEventListener("click", () => {
      if (state.mediaDetailsItem) void deleteMedia(state.mediaDetailsItem, ui.mediaDetailsDelete);
    });
    window.addEventListener("resize", () => {
      if (!ui.mediaPreviewImage.hidden && state.mediaPreviewScale > 1) renderMediaPreviewTransform();
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
      cancelCameraScan();
      stopMediaAutoImport("backgrounded");
      lutPreviewRenderer?.dispose();
      lutPreviewRenderer = null;
      cancelMediaDownload({ silent: true });
      cancelMediaUpload({ silent: true });
      clearMediaThumbnails();
      closeMediaPreview();
      closeMediaDetails();
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
    if (connectionGuidance.availableUsbEngine(state.health) && !state.health.authRequired) await scanCameras();
  }

  initialize();
})();
