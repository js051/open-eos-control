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

## 送 CI 前的整合測試契約核對

獨立來源審查發現 Cancel 案例原本等待 ViewModel 的全部子工作完成；整合媒體基底後，下載紀錄的 lifetime collector 正確地持續存活，因此這個條件不可能代表連線取消完成。改為在 Connect 前記錄已存在工作，held identity 到達後只追蹤本次新增工作。保留工作非空、全部完成、pending 清空、舊回覆未放行、沒有額外請求及手動重連完成的斷言；不終止 collector，也不放寬 15 秒。這是測試修正，不宣稱是舊 CPU 驗證拒絕案例的根因。

這次僅 androidTest 修正於 2026-10-05T12:11:45Z 至 2026-10-05T12:12:55Z 完成編譯、Lint及test APK，exit 0（1m10s）。Lint仍為0 error／55 warning／2 information；App APK未變，775 JVM沒有重跑。最終來源manifest為 `ab2d1a5c0cc7133b9815ff0c9928c06501cb01dfcfc97c3b50ee0d079ac2b634`，test APK為 `1d51263e5174b990bcec17ff5cda9163d639ce319bed7c03aefcb469d4c93b51`；仍待實跑，不將這次編譯當成取消案例通過。

## 裝置 gate 與已知限制

- 連線原獨立來源曾於 CPU-only API34 實跑：0 confirmed pass、1 assertion failure、1 interrupted、6 not run。手動修改驗證資料後的 assertConnected 15 秒 timeout 尚未定因；模擬器稍後 SIGKILL 不能用來替第一例歸因。整合保留原斷言與安全診斷；下方首輪加速 CI 已建立當前來源的通過證據，沒有倒推舊 CPU 失敗根因。
- 新錄影兩例在送首輪 CI 前僅編譯；下方首輪兩平台均已實跑通過：無 event 延遲 MP4、耗盡後只讀重查。它們驗收真正 App／HTTP 路徑、有效 H.264 的 PlayerView 尺寸與播放進度、MediaStore video／解除 pending／原始 bytes，以及 COMPLETED 下載紀錄，另核對相機 Start／Stop 各一次。
- 影片查找僅是已載入及既有有界候選的已知集合，不能保證全卡排除或檔案因果。Stop ACK 後 status readback 失敗沒有專用 typed acknowledgement，本批維持原錯誤語意，不猜停止成功、不自動重播。
- 尚無新增物理相機、手機網路、USB 權限或第三方 SAF provider 證據。自動重連、背景匯入、預覽 Close 待處理項目與 PR #202 實驗不在本批。

## 第一輪整合 CI 與 JVM 清理反例

PR #206 首輪 head `407ed6b4dd0f86071c2301f4e66fb5acbd3ed390` 的 [CI 37308457268](https://github.com/js051/open-eos-control/actions/runs/37308457268) JVM為 **774/775**；`histogramAndWaveformRemainMutuallyExclusive` 在測試開始前拋出 `UncaughtExceptionsBeforeTest`，並非 histogram／waveform 產品斷言失敗。原 job 預設只印例外類型，沒有 suppressed cause 或 XML artifact；不能僅憑這行 log 宣稱根因已確證。

來源查核發現新錄影 fixture 在 HTTP idle／ViewModel scope 完成後就重設 Main，但 `disconnect()` 的 NonCancellable 清理並不隸屬於該 scope。以真 ViewModel／Repository／HTTP 將取消 event subscription 的 DELETE 扣住：scope 已完成時，舊 fixture helper 仍會錯誤視為清理完成。新增獨立斷言精確失敗為 `Completed ViewModel scope cannot substitute for detached repository cleanup`，1/1 紅；不是刻意使拍攝或對焦指令失敗。

修正 fixture 在 Main 仍安裝時等待 repository 清理鎖並泵入同一測試排程器，DELETE 確認後才結束；不更改產品 disconnect、安全停止或連線 timeout。相同反例與錄影、連線、preview、scope 四組 focused **45/45**（20＋10＋14＋1，0 failure/error/skip）通過，terminal exit0，59秒。這確證測試清理條件缺漏；尚未取得原 CI suppressed exception，仍保留兩者因果關聯的證據界線。

先以人工 canary 發現 Gradle 原生 FULL formatter 仍漏 suppressed 訊息，故改用只在失敗時輸出 Throwable 完整階層的 TestListener，使後續非同步失敗能保留 cause／suppressed 訊息；沒有新增 logger 至產品、放寬斷言、隱藏失敗或改 workflow／required checks。最終 aggregate／裝置 CI 待本次來源獨立驗證。

### 清理修正與診斷來源的本機完整檢查

12:57:53–13:12:52 UTC 的同來源 aggregate 實際完成 **776/776 JVM、69 suites、0 failure/error/skip**，Lint **0 error／55 warning／2 information** 與 AndroidTest Kotlin 編譯；最後 test dex merge 階段 daemon 消失，整指令 exit1，原因未證實。沒有重跑已完成項目：同一來源於 2026-10-05T13:18:21Z–2026-10-05T13:18:44Z 只補 `assembleDebugAndroidTest`，**exit0、21秒**，完成 test APK。

兩個指令的來源 manifest 前後完全相同，SHA-256 `bc5e8ad77ab3491aa874075592b3f1c8d7d6362bb21266da188acb824718b61d`；build.gradle.kts SHA-256 `0056bad85b2c7839a5d0667b8e41d8cb1de308182318978910ce84badfe5a48f`。App APK仍為 `8a40e09c225cf177dec0cc78fa6ea8178488c08deca392012e80714a9bd9991d`；新test APK為 `e77c70e654b2b9cfe7b1f22e6668e89e81183d1c1c9c38b27db0e95e8bf8a5b9`。兩段證據共同完成本機gate，第一段非零exit仍保留。

人工失敗canary實際證實新失敗輸出同時含primary、cause與suppressed三個合成標記，且預期exit1；該臨時fixture已移除，最終776項不包含它。此來源仍需新的精確head CI，兩個layout失敗的原斷言沒有放寬。

### 首輪 API34／36 原始結果

同一 head `407ed6b` 兩個平台各 **272/274，2 failure，0 error/skip**，失敗清單相同：`localizedRecoveryAndConnectionChoicesWrapAtLargeText` 的繁中「直接連相機」，以及 `cancelStaysTouchableWhileTheNarrowLargeTextFormIsScrolled` 的英文 Connect，均觸發 TextLayout 的 visual overflow。原紀錄沒有尺寸、方向或失敗畫面，不能先將其歸為固定高度或忽略成字型誤差。後續保留原斷言，補實際約束、段落與文字尺寸、字級／密度、overflow方向及僅合成輸入的失敗 PNG。

原先待閉合的兩個 native CCAPI App 連線案例，以及兩個有效 H.264→PlayerView→MediaStore→COMPLETED 紀錄旅程，在兩平台均已實跑通過。這只建立該來源的模擬器證據，不能倒推舊 CPU-only timeout 的唯一根因或代表物理相機已驗證。

## Release Assessment

- Latest release baseline：`v0.11.0` Development Preview。
- Proposed impact：`minor`；取消與失敗恢復是完整新增可見流程，錄影查找是既有流程修復。
- 未改版本。此 PR 基於媒體 PR #205，須先完成相依批次及本批精確 head CI，不能直接宣稱 main accepted 或 preview released。
- 目前 unresolved gate：本批仍有兩個大字級 layout 失敗，JVM 清理修正與後續來源亦須通過精確 CI；四個連線／影片旅程已有首輪來源通過證據。不能弱化斷言或僅延長 timeout。
- Physical-device status：pending；fixture／模擬器證據不轉為相機相容性宣稱。
