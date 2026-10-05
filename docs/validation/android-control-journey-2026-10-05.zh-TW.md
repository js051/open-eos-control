# Android 連線恢復至停錄保存整合驗收

本批將「連線失敗 → 修改／取消 → 明確重連 → REC Start／Stop → 查找新可見影片 → 預覽及保存」收斂為一個控制旅程。依賴媒體 PR #205，基底為 `1c672d5f18d084576fba23c2ffff518e5f4749d6`；該精確基底的 [CI 37302730469](https://github.com/js051/open-eos-control/actions/runs/37302730469) 已全數通過適用 gate，API34／36 原始 XML 各 264/264、0 failure/error/skip。本批自己的裝置測試與 CI 仍須獨立通過，不繼承基底的 runtime 結論。

## 整合與使用者契約

- 編輯等待中的連線設定會取消舊 attempt，新連線先等舊責任清理；晚回覆不能讓新網址顯示舊相機或讓新 Bridge 來源顯示舊清單。編輯、Dismiss 與 Cancel 都不隱式重連。
- 未連線時的 CONNECT／BRIDGE 等待提供固定可達取消；實際 HTTP／網路失敗提供英繁中恢復指引。驗證拒絕展開欄位，再由使用者明確重試；USB 不可連線的原因與已完成但找不到相機的 Bridge 掃描分開說明。
- 明確 Stop 正常回傳非錄影後，沿用既有有界 listing 與重查，排除操作前已知素材，僅選影片。耗盡提供只讀重查，不能重播 Start／Stop，也不將「新可見」當成「確定由這次錄影產生」。
- 保留基底離線下載紀錄入口、完成檔案保存責任、日期／評分篩選與隱藏選取。ConnectionScreen 的衝突採保留固定 Cancel、可捲動恢復表單及表單內離線紀錄入口；CHANGELOG 保留全部 Unreleased 事項，沒有覆寫整份 ViewModel。

詳細因果證據分別在[連線恢復](android-connection-recovery-2026-10-05.zh-TW.md)及[停錄影片查找](android-recording-media-review-2026-10-05.zh-TW.md)。來源分支的歷史測試數維持原輪次；以下為真正整合來源的結果。

## 來源與本機驗證

整合依目的保留原始提交訊息與 Source-Commit 來源：連線五筆 `f83110f`、`6e1c6d9`、`6d5afad`、`5108b0b`、`fd32510`；錄影兩筆 `73dba49`、`8a73a7b`。納入媒體基底後兩筆測試修正，最後以不變 tree 的本地合併對齊 PR #205 真正祖先。沒有將其他未驗實驗或 main 修改帶入。

凍結驗證來源為 `fc5d59802a614d3bab09cea9a81c0562792a2a25`，tree `877c4009ffb537b30ea507974af544c920381a69`；對齊祖先後 `4dca88dde07a36ba3344a723e9f1a896f885a59f` 的完整 tree 相同。本文只是後續文件，不改驗證過的產品或測試來源。

1. 2026-10-05 11:30:24–11:40:23 UTC，AndroidTest 編譯完成，JVM **775/775、69 suites、0 failure/error/skip** 實際重新執行。接著 Gradle daemon 在 Lint 階段消失，整個指令 exit 1；原因未證實，不能稱該指令全綠或推斷為 OOM。
2. 保留 XML／失敗紀錄，確認來源不變後只補 Lint 與兩 APK，沒有重跑或重算 775 JVM。11:46:49–11:48:40 UTC，指令 **exit 0**：Lint **0 error／55 warning／2 information**，App／androidTest APK 成功。AndroidTest 編譯沿用上輪已完成產物。
3. 前後來源 manifest SHA-256 均為 `e2758dc7b84ac241502f6d09bcd5cef03a396738307d30694c6f72737552c0cd`。兩個指令的結果合起來完成必要本機 gate，第一個失敗仍保留。

App APK SHA-256：`8a40e09c225cf177dec0cc78fa6ea8178488c08deca392012e80714a9bd9991d`；androidTest APK：`f8c6b0a19ee7402d219af15ced85363c78f8eb0701b7cc07e6a4868ffdf84a20`。額外以官方 dexdump 核對新舊關鍵測試 class 確實存在，且 test APK 含既有有效 H.264 asset；這不等於執行測試。

## 裝置 gate 與已知限制

- 連線原獨立來源曾於 CPU-only API34 實跑：0 confirmed pass、1 assertion failure、1 interrupted、6 not run。手動修改驗證資料後的 assertConnected 15 秒 timeout 尚未定因；模擬器稍後 SIGKILL 不能用來替第一例歸因。整合仍保留原斷言與安全診斷，需在正常加速 CI 閉合。
- 新錄影兩例已編譯，尚未實跑：無 event 延遲 MP4、耗盡後只讀重查。它們驗收真正 App／HTTP 路徑、有效 H.264 的 PlayerView 尺寸與播放進度、MediaStore video／解除 pending／原始 bytes，以及 COMPLETED 下載紀錄，另核對相機 Start／Stop 各一次。
- 影片查找僅是已載入及既有有界候選的已知集合，不能保證全卡排除或檔案因果。Stop ACK 後 status readback 失敗沒有專用 typed acknowledgement，本批維持原錯誤語意，不猜停止成功、不自動重播。
- 尚無新增物理相機、手機網路、USB 權限或第三方 SAF provider 證據。自動重連、背景匯入、預覽 Close 待處理項目與 PR #202 實驗不在本批。

## Release Assessment

- Latest release baseline：`v0.11.0` Development Preview。
- Proposed impact：`minor`；取消與失敗恢復是完整新增可見流程，錄影查找是既有流程修復。
- 未改版本。此 PR 基於媒體 PR #205，須先完成相依批次及本批精確 head CI，不能直接宣稱 main accepted 或 preview released。
- 目前 unresolved gate：本批 API34／36，尤其原連線 UI 失敗與新增影片保存旅程；若 CI 失敗需查清修復，不能弱化斷言或僅延長 timeout。
- Physical-device status：pending；fixture／模擬器證據不轉為相機相容性宣稱。
