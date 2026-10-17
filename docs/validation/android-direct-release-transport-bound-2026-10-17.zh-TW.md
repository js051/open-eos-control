# Android Direct CCAPI AF／Bulb 控制傳輸有界化

## 問題與最小修正

基線 `25a67ff9fcf4519451d83e13f44356971bc1fdac` 的 Direct CCAPI held AF、pulse AF、manual AF fallback、半按快門，以及 Bulb Start／Stop 都經 `commandOk → requestOk → mutationHttpClient`。這些呼叫沒有 still／Live View／recording／maintenance 標記，因而沒有 whole-call timeout、取消 watcher 或 response-body 上限。原有 `HeldAutofocusSession`、`ShutterReleaseSession` 會持有 mutex，並以 NonCancellable 等待原始 release；慢速同步回應因此可能擋住取消與清理。

本批只在上述 10 個真相機 press／release 呼叫加入 `releaseControl = true`，由 `commandOk` 明確轉送至 `requestOk`：

- 選用既有 single-attempt client，whole-call 最長 15 秒，保留呼叫端較短期限；禁止自動重試、redirect 與 authentication replay。
- 取消 watcher 取消原 Call，但仍同步等待 execute、body 與 response close 完成。
- 回應最多 65,536 bytes，未知長度也逐次限制；已宣告長度必須完整。
- AF／Bulb 與 Live View 共用有界純文字讀取，保留 HTTP 錯誤類型與文字；不新增嚴格 JSON 收據要求。
- 既有 still／recording／shooting-setting 的嚴格 JSON 讀取優先，避免未來旗標併用時降級。實際 AF／Bulb callsite 未與 stillCapture 併用。

兩個 release owner 檔案與基線逐 byte 相同。未改 mutex、NonCancellable、pending release、原始 operation closure、失敗後 stop-only retry 或 Bulb unknown 狀態；沒有 detached cleanup、owner protocol 重寫或 Simulator 路徑修改。

## 已執行驗證

2026-10-17 UTC，使用既有、SHA-256 核對通過的 Kotlin／JUnit 快取，離線執行。每個 JVM 限 256 MB／1 CPU。

- 同一套 production-entry host fixture：基線 compile exit 0、JUnit exit 1，14 案中 12 案於指定 assertion 失敗，2 案相容性檢查原已通過；修正後 compile exit 0、JUnit exit 0，14/14 PASS。runner 僅接受預期的 12 個 assertion 失敗，不能把缺依賴或編譯失敗當 red。
- 12 案問題驗證涵蓋：held AF cancellation 到原 Stop 並保持 join；pulse AF、manual PUT fallback、explicit half-press 及 Bulb Start／Stop 的有界 wiring；取消 Bulb press 到原 release；已知／未知長度超量與截短回應；未知 Stop 阻擋新操作並只重試原版本的 release；取消 NonCancellable Bulb Stop 不分離 producer；超量 AF Start 不執行 held callback，仍完成 release。
- 2 案相容性檢查：純文字 HTTP 400 維持 typed failure 與 release retry（並檢查 stillCapture／releaseControl 併用仍執行 strict JSON）；Simulator 原 AF／半按／Bulb endpoint 與 payload 不變。
- 逐字萃取 production AF／Bulb entries、commandOk、requestOk，編譯完整 `HeldAutofocusSession`、`ShutterReleaseSession` 與 bounded readers。主要案例由真實 entry 轉送 flag，fixture 沒有替 entry 手動 opt-in。
- 分開的 source guards 檢查 15 秒條件式 cap、保留較短期限、禁 retry／redirect／auth replay、one-shot factory routing、10 個 callsite 互斥旗標、strict JSON 優先，以及兩個 owner 檔案未改。
- 共用 `requestOk` 的既有 Live View 12/12 與 still 3/3 host 回歸通過。
- Production client 與新增 MockWebServer 測試通過 Kotlin PSI 語法檢查；新增原生測試有 9 個互不重複的方法名。這不是完整 Android type-check。
- `git diff --check` 通過。正常 pre-commit secret scan 必須通過才建立本地 commit。

[收據、JUnit 原始輸出與 manifest](evidence/android-direct-release-transport-2026-10-17) 保存 production／method／fixture／工具 SHA-256、退出碼與邊界。交付 receipt 將 filename／SHA 分列，manifest 同時保存原始 receipt hash；JUnit 僅正規化尾端空白行，另保存未正規化 hash。完整本地 evidence 保留，未提交可重建 JVM binary。

可重現：使用 `scripts/validation/run-android-direct-release-transport-host.py`，指定既有 installation receipt 目錄為 `--jars`，分別以 `--phase baseline --ref 25a67ff9` 與 `--phase current` 執行到不同 `--out`。既有 Live View／still runner 的 `--phase current` 用於回歸。

## 未執行與證據邊界

`CcapiDirectReleaseTransportTest` 已編寫 9 案實際 OkHttp／MockWebServer 測試：

1. 取消 held AF Start，join 原 Stop 的慢速 body。
2. 取消 Bulb Start，join 原始 release。
3. 預設 client 無 call timeout 時，AF NonCancellable Stop 的連續滴流由 production 15 秒 cap 終止。
4. 保留 250 ms 呼叫端期限，未知 Bulb release 阻擋新 Start，明確 stop-only retry。
5. pulse AF、manual PUT fallback、explicit half-press、Bulb 的真實 Call timeout wiring。
6. 超量 AF Start 仍 release，且不進 held callback。
7. 未知長度 chunked AF Stop 超量，保留 retry obligation。
8. 截短 Bulb release 不得確認成功。
9. AF redirect 不得重播 Start，仍嘗試原 Stop。

上述 9 案均為 NOT_RUN：既有核對快取沒有 OkHttp、Okio、MockWebServer；依授權未下載依賴、未執行 Android build。完整 Android type-check、原生 JVM tests、exact-SHA CI、實機驗收也均為 NOT_RUN。

Host 的 Call／Response／InputStream、operation discovery、status 與 Simulator postJson 是 synthetic collaborator；它驗證 production 控制流程、body byte bound 與 join，不證明真正 socket 15 秒期限或 Android／相機時序。source guard 不能替代未執行的真實 OkHttp 測試。

非目標：status／capability／temperature 前置或後置 reads、整條 Connect／Disconnect 的總期限、其他 command transport、Android Bridge 或 USB、UI／native decoder、版本變更、合併或發布。不宣稱所有等待點已修復。

## Release assessment

影響為 patch：修正既有 Direct CCAPI AF／半按／Bulb 控制在取消與 release 時的傳輸限制。本批僅 implemented，尚無 exact-SHA CI、完整 Android 或實體相機證據；不宣稱 PR ready、main accepted 或 preview released。
