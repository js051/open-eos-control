# CCAPI 快門自動對焦選擇

## 產品範圍

讓「完成一次可信拍攝」流程可以明確選擇拍攝請求是否要求自動對焦，再沿既有最新素材入口檢查新 JPEG。

- 新可達流程：iOS Direct CCAPI、PC 瀏覽器 → Bridge CCAPI。
- Android Direct 保留既有 AF 選擇與拍攝保護；手機 Bridge client 增加可選能力解析與拒絕不支援 AF 關的契約。
- 手機 Bridge 連線 UI 目前只設定本機 USB engine。本批不新增手機透過 Bridge 設定 CCAPI 相機位址的入口，也不宣稱該連線流程已可使用。
- 不擴張 held AF、USB、libgphoto2、EDSDK、simple simulator 能力；不改 Bulb 所有權、釋放責任、版本或發版狀態。

## 行為契約

1. 每次新連線預設 AF 開，切換只改本機當次連線意圖，完全不送相機指令。
2. 一般拍照在開始時保存選擇，依相機廣告送 ordinary shutter POST，或 manual shutter POST/PUT。`af` 是嚴格 JSON boolean；manual release 始終是 `af:false`。
3. 相機或網路拒絕 AF 關時不改 AF 開、不自動重送快門。既有釋放補償仍保留。
4. Bridge 的 `shutterAutofocusSupported` 是向後相容的可選欄位。只有廣告可用 Canon 快門操作的 CCAPI engine 為 true。
5. 空 request body 與 `{}` 保留舊的 AF 開行為。字串、數字、`af:null`、未知欄位及錯拼欄位在伺服器端拒絕。
6. 新 client 對缺少／錯誤型別／false 能力先本機拒絕 AF 關，避免舊伺服器忽略 body 後仍 AF 開拍攝。能力在同一連線撤回時不偷偷改回 AF 開；使用者須明確選回 AF 開，或等待能力恢復。
7. 英文／繁中 UI 說明「拍攝請求不要求自動對焦」。不代表光學鎖焦、AF/MF 切換或關閉 Servo／連續 AF。

## 自動測試來源

| 層級 | 生產路徑與負例 |
| --- | --- |
| Bridge HTTP | `test_shutter_autofocus.py`：舊空 body、true/false、嚴格型別與未知欄位、gphoto2 零呼叫拒絕、manual POST/PUT、press 失敗後僅釋放 |
| PC UI → HTTP → Canon fixture | `ccapi.browser.test.js`：切換零快門、繁中說明、能力撤回不降級、busy 時切換及重按不新增命令、AF 關一次拍攝 → 新 JPEG → 最新素材預覽 |
| iOS Core | `ShutterAutofocusTests.swift`：Direct/default/false/manual、錯誤不降級、simple simulator 拒絕、Bridge 嚴格能力及重新連線清除 |
| iOS 真實 loopback TCP | `URLSessionShutterWireTests.swift`：ordinary manual AF 關收到丟失／截斷回覆，POST/PUT full press 都只發一次且仍釋放 |
| iOS app state | `ShutterAutofocusStateTests.swift`：切換零 HTTP、busy/repeat、video gate、legacy、capability withdrawal、錯誤後只有明確再拍才重送、session reset |
| iPhone UI → HTTP | `testCanonicalShutterAFChoiceReachesCameraAndNewJpegPreviewInBothLanguages`：英／繁中切換、44pt 控制、AF 關 → 新 JPEG preview、重連預設 |
| Android client / UI state | `DesktopBridgeShutterAutofocusTest`、`CameraUiStateTest`：能力嚴格解析、不支援／legacy 零送出、失敗單次及 session 清除 |
| Android 生產 UI / HTTP | `CameraFocusSessionTest` 保留 Direct 真 UI → HTTP；新增 legacy Bridge 不顯示 AF 選擇且預設 true。可用 Bridge AF 的 JVM fixture 只驗 client 契約，不能作為已存在的手機 CCAPI Bridge 連線流程證據 |
| Android 最新素材 | `CameraCapturePreviewListingTest`：Direct AF 關 → 新素材／預覽與連線重設；既有相簿選擇器回歸保持 |

## 驗證狀態

本文件記錄測試範圍，不把尚未執行的測試當成通過。

- 已完成輕量檢查：修改 Python 的 AST、Android strings XML 與重複 key、JavaScript 語法、`git diff --check`。
- 本機 JavaScript 語法檢查使用 Node 24，不等同 CI Node 22 browser suite。
- 本輪 Python 測試環境未取得可核實的安裝完成結果，未執行 pytest／ruff；原始 `412b210`＋先寫測試的 red 對照仍待執行。
- Android 凍結來源 `b2316a3a` 已通過七類 focused JVM 72/72，0 failure/error/skip。首輪的 `MaterialTheme` import 編譯錯誤，以及後段把 member `assertDoesNotExist` 誤作 extension import 的錯誤，都已修正；失敗紀錄保留。
- 提交 `7ac6b3d055f52cd05736addd3dd5c111888caf5f` 的本機完整 App JVM 969/969、Camera Import contract 14/14 已由當輪 fresh XML 核實，合計 983 項、0 failure/error/skip；前述 focused 72 是子集，不相加。AndroidTest Kotlin／Java 編譯亦完成，559 個來源檔在執行前後完全一致。
- 該完整建置在 Lint 階段未取得整輪終態，沒有 Lint XML 或 APK 產物；原執行保留為 unknown，不能宣稱完整 gate 通過，也不重跑已證同來源的 983 項。Lint／APK／Android 裝置、PC browser、iOS Core／app／UI 與適用遠端 CI 仍須收齊。Linux 不提供 Xcode，JVM 證據不能代替其他平台或裝置執行結果。
- 目前沒有實機相機證據。所有相機互動測試是明確標示的 synthetic protocol fixture；不得宣稱鏡頭不移動或任何 EOS 機型相容性已經實機驗證。

## 第一輪遠端驗收與修正（2026-10-07 UTC）

來源 `fd912a28ff74ecac88f993fa502ae7d2b6f2dcda` 的 [CI 37657345834](https://github.com/js051/open-eos-control/actions/runs/37657345834) 已完成：API 34／36 原始 XML 各 355/355、0 failure/error/skip；iOS Core 244/244、App 106/106 通過。Bridge Ruff、Node 22 腳本與真 PC browser、Windows bundle 及 Android JVM／APK／signer 通過。這些結果只屬該來源，整輪仍失敗，不能稱本 PR ready。

- Bridge pytest 的新 unsupported 負例把既有 HTTP 409 契約誤寫為 501；修正期待值並確認 `UNSUPPORTED_FEATURE`、`STILL_CAPTURE`、`libgphoto2`，保留零相機呼叫與後續 legacy 拍摄斷言。
- Simulator 47 項通過、1 項失敗，因原 exact state dict 漏掉新 `shutter_af_requests: []`。補入該欄位，保留完整 dictionary 相等判斷，未改成 subset。
- iOS UI 為 19 項、4 項失敗。新 AF 測試查到 native Switch 的 AX frame 為 31pt；這不證明外層 44pt row 的實際 hit area。產品改用 label 明確持有至少 44pt 大小與 content shape 的 Button，保留本機切換、能力／忙碌 gate、selected 及英／繁中 On/Off value。測試仍要求至少 44pt，未降低尺寸。
- 原 trace 顯示 AF case 已 teardown 後仍執行 UI；其 waiter 失敗被記到 Direct case，後續又污染 LiveView 與 Offline case。Apple 說明 `continueAfterFailure=false` 使用 Objective-C exception，穿過 Swift async frame 的行為未定義，見 [XCTest 遷移說明的失敗控制段落](https://developer.apple.com/documentation/testing/migratingfromxctest?language=objc)。五個 async case 改用可繼續記錄斷言的 XCTest 設定，再由 `XCTUnwrap` 的 Swift throw 立即結束失敗流程，並 defer 終止 App；同步 recovery case 保持原設定。
- 19 個案例與原命令條件保留；#214 的單次 tap、5 秒期限、value／cardinality／44pt 條件保留，取消不再被 polling 吞掉。後三個 UI 失敗不是乾淨的獨立產品回歸證據，必須在生命週期修正後重跑整套驗收。

上述修正尚待新的 exact-head CI。補缺的本機 Lint／APK 執行亦未取得終態，仍沒有 Lint report；原 983 項 JVM 證據保留，不因此重跑或冒稱 Lint 通過。

## Release Assessment

- 截至 2026-10-07 16:17 UTC，公開版本仍為 v0.12.0 Development Preview；開發基底 v0.13.0 已在 `fb4f555` 完成 main acceptance 與不可變候選，尚待 tag／發布。本 feature batch 不調整版本，也不納入該凍結候選。
- 影響：`minor`，新增 iOS／PC 使用者可控制的快門 AF 能力；發布仍需獨立版本／驗收流程。
- 阻擋：上述待執行的同來源 runtime gates 與後续 review；目前只可供程式碼審查，不能稱 PR ready。
- 實機狀態：pending。後續實機驗收須記錄相機、傳輸、app build、AF 模式及新 JPEG 交付／預覽結果。
