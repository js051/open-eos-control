# iOS 選定語言與錯誤恢復文案（2026-10-17 UTC）

## 原功能列與本批界線

基底 `aa4a56c37efd58db0031956188002f89130d486f`。本批承接原 89 列的 **#128 iOS camera-control app／G13 英繁中與遇錯恢復**，涉及既有 G03/G04/G06/G07 恢復訊息，沒有新增操作或 transport。#36 Discovery and API versions、#127 iOS CCAPI core 的 connection caller 是本輪檢查範圍，但沒有因這次文案修正標記為已完成。

以下是實際 caller/source/resource 證據，**不是 iOS UI 已觀察結果**。本環境 `command -v swift`、`swiftc`、`xcrun` 均無結果；Swift compile、XCTest、原生 alert、VoiceOver、Dynamic Type 與真相機均 **NOT_RUN**。沒有使用模擬翻譯程式冒充 production Swift 執行。

## 確定的 source 反例

1. `LanguageSettingsView` 使用現有 `AppLanguageStore.select` 選 English／繁體中文／跟隨系統。
2. `OpenEOSControlApp` 原本只將該 store 與 `language.locale` 注入 SwiftUI。`language.string/format` 從使用者選定的 bundle 取文案；不改系統 locale。
3. `CameraAppState` 的 app 自有錯誤卻呼叫 `NSLocalizedString`，使用 main bundle 的系統語言。這些結果是已完成的 `String`，經 `lastError` 傳給 `RootView` alert、Media／Debug 入口，不會因 SwiftUI locale 自動重新翻譯。
4. `CameraControlView.CaptureBar` 的 REC 原始回應／目前觀察兩行也用 `NSLocalizedString` 取得模板及嵌套值。

因此，系統英文而 app 選繁體中文時，既有控制標籤可為中文，新產生的 LUT render／capture readback 等恢復訊息及 REC evidence 卻仍為英文。反向設定同理。這是可直接由 owner 與 bundle 路徑確定的 mismatch；本輪沒有在 iOS 裝置重現畫面。

## 窄修正與狀態所有權

- `OpenEOSControlApp` 建立單一 `AppLanguageStore`，傳入全部五個 production／DEBUG fixture 的 `CameraAppState` 建構入口，並沿用原 environment object／locale。
- `CameraAppState` 保留同一個 live store reference。沒有顯式注入時，從既有 `defaults` 讀取儲存的 app 選擇，讓現有 test／preview caller 保持可用。
- App 自有訊息改用 `language.string`：render stall、AF unavailable、capture status readback、capture configuration、maintenance partial failure、REC rejection／start-not-confirmed、shooting-setting readback、LUT render failure。
- LUT import 的 app 自有模板改用 `language.format`，傳入的原始 `error.localizedDescription` 保持原樣。
- REC 兩行改用同一個 `language.format` 與 `language.string`，模板、Start／Stop、response evidence、current observation 共用所選語言；既有 view environment observation 會重繪目前 evidence。

切換語言不重建 connection、清空 history 或產生新的 error owner；已儲存的錯誤文字保持到原本正常的清除／替換時機，**之後新發出的 app 自有錯誤**採當時選擇。`record(_ error:)` 繼續直接保存原始 `error.localizedDescription`，不把 device/server 診斷當 localization key。沒有設定 `AppleLanguages`、替換全域 bundle 或改 OS 語言。

## 跨平台既有對照

- Android `AppLanguageManager` 使用既有 application locales；`OpenEosControlApp.kt` 的 REC recovery 透過 `stringResource` 選取 template／response／observation。這裡只確認既有 caller 走 application localization，沒有重跑 Android native 或宣稱所有 Android 錯誤均已本地化。
- PC `app.js` 的 `t` 由 `resolvedLanguage()` 選目前 app preference；`renderRecordingHistory` 的 response／observation 各走 `t`。本批沒有改 PC，也未增加 browser 驗收結果。

## 新增測試與本地結果

`RecoveryLanguageTests.swift` 有五個已撰寫 native tests，位於既有 required `Tests` target：

1. 同一個 live store 依序 English → 繁中 → English，呼叫實際 `reportCubeLutRenderFailure`；新錯誤應比對指定 bundle，preview／screen／既有錯誤不因選語言被重設。
2. 呼叫實際 `reportCubeLutImportError`；app 模板依選擇改變，synthetic 原始診斷完整保留，`CancellationError` 不取代目前錯誤。
3. 未注入 store 的 state 使用既有儲存偏好。
4. system 選項保留 main bundle fallback。
5. 既有 store 的 REC 模板與嵌套 evidence 在兩種選擇均一致。這一項測 store/resource；view caller 接線由 source guard 檢查，並非 render 測試。

以上五個 XCTest **全部 NOT_RUN**，尚未編譯；不是 native PASS。

實際已執行：

- 本批 source/resource guards：**5/5 PASS**。
- 全部 `test_*source.py` source guards：**143/143 PASS**，包含本批五個。
- iOS project source paths：**4/4 PASS**，確認新測試仍由現有 `Tests` directory 包含。
- 對真正的 `git show aa4a56c3` 三個 production 檔，shared-owner 與 selected-recovery 兩個 guards 都拒絕；修正版通過。**2/2 static negative controls**，不是 runtime reproduction。
- `git diff --check`：PASS。

## G13／connection 本輪查核與 residual blockers

已查 `ConnectionView` 欄位／typed guidance／scan／retry／cancel／auth／focus／accessibility identifiers，`CameraAppState` connect／cancel／offline／scan owner，`ConnectionRecovery`、`RootView` scene lifecycle／sheet／alert／安全恢復欄，及 app bootstrap／language settings。

本輪另無確認的 connection 狀態缺口：既有取消後改設定明確重試、scan 與 connect gate、late result 的 session/generation 隔離及 offline replacement 路徑保留。這只是有界 source 審查，不是證明沒有其他 bug。

仍需原生驗證的精確範圍：

- 混合 system/app 語言的實際 alert、REC evidence、恢復畫面與重啟後偏好。
- 失敗後 edit/retry、取消／背景／重連在實際 iOS 網路／權限與 keyboard focus 下的操作。
- VoiceOver 朗讀順序與狀態通知、大字／方向／長字串遮擋、touch target、OS Files picker／權限流程。
- 真 camera／Bridge 回應、物理 shutter／REC／Live View。此文案修正不替代這些 backend／硬體驗收。

## 交付狀態

本文件記錄 task worktree 中已實作的窄修正和 host source 證據；沒有新增 iOS 建置產物、版本、main 合併、tag 或發布。後續 dev 交付須以獨立 exact-commit readback 為準，原89列與原生 NOT_RUN 不因此變為全通過。
