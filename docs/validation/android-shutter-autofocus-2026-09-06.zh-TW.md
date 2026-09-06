# Android 快門自動對焦選擇

## 範圍與依據

本輪新增 Android 直連 CCAPI 的拍照 AF 選擇。Canon Android sample 的快門處理會讀取 AF checkbox，將選擇傳入一般快門或 manual full-press 請求：[固定版本的 RemoteCaptureFragment](https://github.com/Chieri-JN/Intervelo/blob/bb826a1211c49a6d3a569ec8c4b9934b585db270/CCAPI_Sample_Android_1.3.0e/Sample/app/src/main/java/com/canon/ccapisample/RemoteCaptureFragment.java)。這是 Canon sample 的第三方鏡像，不是 Canon 官方下載站；本 repo 未複製 SDK 程式碼或文件。

只有相機明確公告 `POST /shooting/control/shutterbutton`，或 `PUT`／`POST /shooting/control/shutterbutton/manual` 時，Android backend 才宣告 `shutterAutofocusSupported`。單純 GET、推測的版本路徑、USB、Bridge 或簡化 Simulator 都不推定支援。能力不存在時，`af=false` 在送出 HTTP 前即被拒絕；其他 backend 的既有預設拍照方法保持相容。

## 操作語意

- Photo 的更多設定新增「快門自動對焦」，預設開啟；關閉時中央快門顯示 `AF OFF`／`AF 關`，無障礙描述改為不啟動自動對焦的拍照命令。
- 選擇只存在目前連線，不持久化；斷線、建立新連線、改用其他 transport 或重新進入離線預覽均重設開啟。
- 選擇經正式 UI、ViewModel、repository 與 backend 傳到 CCAPI 的 JSON boolean `af`。manual full-press 的 release 一律使用 `af=false`，即使 press 失敗仍沿用不可取消的清理流程。
- 忙碌、錄影或 AF-ON 尚未釋放時不能改選；Video 與 Bulb 不顯示此開關，既有行為不變。溫度限制仍會在快門前重新確認，不因關閉 AF 而繞過。
- 不修改鏡頭 MF／機身 One-Shot、Servo、追蹤或 AF method。關閉只代表這個快門請求不要求啟動 AF，不代表光學合焦、焦點鎖定或排除其他機身對焦行為。
- 原有按住 AF-ON 與 App 快門互斥仍保留。可以放開 AF-ON，再使用關閉快門 AF 的拍照；不宣稱同時按住拍攝已完成。
- 離線預覽只展示操作。診斷新增 `shutterAutofocusSelectable` 與 `shutterAutofocusRequested`，描述能力及要求值，不偽造相機回報的對焦結果。

## 驗證

- 協定單元測試涵蓋預設 true、選擇 false、公告 POST／manual POST／PUT、失敗後 release、GET-only 拒絕、Bridge／簡化 Simulator 拒絕與溫度限制。
- 狀態測試涵蓋模式、能力、忙碌、AF 持有互斥與離線重設。
- 手機測試使用獨立 `.debug` APK 與本機 MockWebServer；正式 UI 路徑驗證關閉選項、拍照送 false、重新連線恢復 true，並測失敗不得偽造拍攝成功。
- 顯示測試涵蓋 360×800、800×360、800×1280、英文／繁中、1.5 倍字體與四個旋轉角度。只驗證本輪開關與快門指示，不將此證據擴張為全部介面排版驗收。
- Android 501 項單元測試、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 通過。HTC U24 Pro／API 34 的 3 項新增 UI 測試及 18 項完整 UI-to-HTTP／連線／對焦生命週期測試分組通過，共 21 項；三種尺寸截圖已檢查。
- 測試曾因先連線再掛完整 App 而觸發路由初始化拒絕；已調整成正式 App 的初始化順序，未放寬產品檢查。旋轉排版測試對已可見列呼叫 `performScrollTo` 會卡住，改為直接驗證可見性；使用明確的 viewport/insets 與沉浸式測試 Activity，不更動系統旋轉設定。
- 本輪沒有向實體相機送出拍照、AF 或設定命令，未覆蓋既有正式 Preview，未操作其他專案的 emulator。
- 操作者目前無法比較機身半按表現；「連 App 後容易失焦」仍是未確認原因，不宣稱此選項修復該問題。R6 Mark III 實拍、One-Shot／Servo 差異及弱網下的機身行為留待真機驗收。

## CI 回歸

首輪 PR CI `34031332701` 的 API 34 通過，API 36 共 143 項只有既有離線手動對焦測試失敗：新增首列設定後，手動對焦標題已在首屏外。測試改為明確使用較短的 360×640 viewport，捲動後驗證標題可見、遠端大步進的回呼參數正確，並可捲回快門 AF 開關；不刪除可見性或操作斷言，也不將更多設定改成無法捲動的擠壓排版。

擴大 HTC 測試時，110 項中另有 6 項無法透過測試輸出服務寫入截圖，1 項刪除確認測試未捲到媒體資訊面板底部便點擊。截圖確認後者仍停留在資訊面板、未送出刪除；測試補上捲到按鈕、確認可見後才點擊，原有確認前不得刪除的斷言保留。直接 ADB 本機測試可加 `-e oecLocalScreenshots true` 將截圖寫入測試 App 私有 cache，避免授予實體手機全檔案存取權；預設 CI 仍使用 TestStorage，兩條路徑都必須成功編碼並寫入，未忽略截圖錯誤。

修正後 HTC 全部 `CameraScreensTest` 110 項通過，連同前述 `CameraFocusSessionTest` 18 項，共 128 項手機測試通過（新增 3 項 UI 測試已包含於 110 項，不重複計數）。最終測試 APK 建包與 lint 亦通過。此證據仍不包含對實體相機的光學或拍攝驗收。

## Release Assessment

基線為已發布 `v0.8.0` Development Preview。新增使用者可操作能力，版本影響為 `minor`，仍不是 stable。功能 PR 不改版號、不建立 tag；發布須另走版本 PR、exact-head CI、main acceptance 與不可變候選流程。本輪只推進 Android 直連 CCAPI，PC／iOS／USB／Bridge 未新增此選項。
