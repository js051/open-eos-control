# Android 讀取取消與重連就緒的後續驗證

## 基準與已確立的新證據

本次接續 `b18cc0ed7173d7ceb60cc64e57f97b7bfe967e3a`。它是原 7 筆本地提交的 GitHub 重建版本，最終 tree 仍為 `149df4446788c17ed2015d086906c0c284783b43`，與原本地 `5a5356b0` 相同。提交內容、每筆 tree、父子順序及真正 pre-push 的 7 筆 outgoing 掃描均已核對；實際作者與 committer 均為原 noreply，提交未簽章。

[PR #194](https://github.com/js051/open-eos-control/pull/194) 為 draft，目標為 main，沒有合併或發布。

原基準的 [CI run 37159350010](https://github.com/js051/open-eos-control/actions/runs/37159350010) 已完成，`head_sha` 精確為 `b18cc0ed7173d7ceb60cc64e57f97b7bfe967e3a`：

| 檢查 | 新結果 |
| --- | --- |
| secret-scan、JVM／APK、ci-complete | 通過 |
| API 34 完整 instrumentation | 181／181，0 failure／error／skip |
| API 36 完整 instrumentation | 181／181，0 failure／error／skip |
| 原 `shutterAfSettingTravelsFromProductionUiToHttpAndResetsOnReconnect` | 兩個 API 都通過，原 8 秒 `awaitFrame` 及拍照 AF 斷言未改 |

上述案例整例耗時為 9.105 秒／7.592 秒，包含多個操作，**不是**單一 `awaitFrame` 耗時。兩份 JUnit ZIP 已依 GitHub artifact digest 校驗：API 34 為 `eacd0fbe74b14e1a0abd81de8ae638eb32cbb0ae36e65c508d90d901fcaa630a`；API 36 為 `1261d3baf181dd0ee95281e913925a041c04dee2203cc1494295f16bbf18a35f`。

這表示原失敗沒有在這次加速 CI 重現，不能把 CPU-only 的失敗改寫為通過，也不能只憑舊版 A/B 同樣失敗就認定產品沒有問題。原 CPU-only 路徑中，第二個 JPEG 請求已收到、Main 曾停頓約 5.96 秒；目前仍未取得足以判定該次卡點的完整 thread／HTTP body 完成證據。

## 故障注入確證的問題與修正

### 1. 連線探測與 RTP 描述讀取漏接取消

對真 `CcapiClient` 的身份 fallback 及 RTP SDP GET，合成 peer 收到請求後保持沒有回應。取消後一秒內應結束的兩個斷言，在基準都失敗，清理階段才主動關閉 socket；沒有用 teardown 例外替代原失敗。

修正將這兩段讀取接上實際 `Call.cancel()`，並保留 `CancellationException`。取消不應嘗試另一版本、建立 native session 或 AUTO 換源。正常 404 換版本、HTTP 200 非 JSON 的原身份 fallback 行為仍保留。沒有改任何相機寫入或 AF 選擇。

新增相容性與取消案例，加上既有 discovery／RTP 回歸，共 **16／16** 通過。兩個額外的首影格邊界原本即通過：

- headers 與 JPEG 前綴已讀後，取消能中斷剩餘 body；不能交付部分影格。
- HTTP 已完成，但 caller dispatcher 尚未執行 continuation 時取消；不能交付影格或記錄成功能力。

這兩個綠測試縮小了診斷範圍，沒有拿來假稱新修復，也未改該影格 HTTP 程式。

### 2. 已開始的輔助影格缺少獨立停止責任

真 ViewModel／Repository／CCAPI 路徑顯示：

- 首影格被阻塞時關閉 Live View 或進入背景，相機端 off 已成功、running／stopRequired 已清除，但初始 GET 與 CONNECT 忙碌仍殘留。
- 首影格位於 reconciliation 內時，停止／明確 restart 可能排在持有 transition mutex 的影格讀取後面。
- full-press 與 release 都已確認後，拍攝流程的附帶影格卡住會保留 CAPTURE 忙碌，進而使背景停止被原相機命令互斥擋住。

修正把每個已開始的輔助影格放入獨立、受追蹤的 child job。停播取消影格；父連線或相機操作繼續完成既有清理。父工作本身取消仍需向上傳播；舊 owner 的 finally 只能移除自己。明確 restart 先釋放阻塞的非 native 影格，再走原 stop／start 序列，沒有新增自動 start 重試。native video 不加入 bitmap-read owner，也不因這個前置步驟暫停 renderer 或破壞其 listener generation。

8 項真 production-path 回歸覆蓋 disconnect／新 session、off、ON_STOP、transition mutex、明確 restart、快速 off→on、拍攝完成後的影格取消，以及相反安全條件：**release 尚未收到 ACK 時，背景切換不得取消 release，也不得提早 off**。這組加既有 Preview／UiState，共 **31／31** 通過；最後 native guard 的受影響驗證與 exact-head CI 另列交付摘要。

新測試曾出現額外 disconnect 失敗。HTTP trace 證明那是合成 peer 只監聽 IPv4、localhost 卻選到 IPv6，stop 在送出前連線失敗；固定測試 peer 的實際 loopback 位址後通過，未放寬 no-replay 策略或精確指令斷言。

## 驗證分層與限制

- 新兩項修正整合後的本地完整 aggregate：**565／565 JVM**、0 failure／error／skip，Lint 與 App／instrumentation 建置成功。這是最後小型 native guard 之前的快照；保留 source manifest、46 份 XML 與 APK hashes，不冒稱是後續修改的完整實跑。
- 最後 native guard 套用後，受影響的 **52／52 JVM** 通過：新增取消 8、Preview 14、UiState 9、RTP 21，無 failure／error／skip；Lint、App 與 instrumentation 建置再次成功。這是 JVM 小組，與前份報告的 52 個 CPU-only 裝置案例是不同集合，不能混算。
- 該次 Lint 為 0 errors／56 warnings。相對原 61 warnings，差異是動態 GradleDependency 提示 9→4，沒有宣稱本輪修了五個依賴問題。4 個 ObsoleteLintCustomCheck registry 仍因需要更新 Lint API 而未執行，不能宣稱完整 custom lint 覆蓋。
- 原重連案例保留；新增只有重連期間卸下畫面 composition 的對照，以及 timeout thread stacks，供分辨 UI／排程因素。對照不取代原完整 UI 案例。
- 已完成的 child 到父 continuation 恢復之間的精確 VM 排程窗口，有 `!stopped` 與父 `ensureActive()` 的 code review 保護，未宣稱新增了直接 production-path 測試。HTTP caller-return 窗口則已有獨立可控 dispatcher 實跑。
- 一秒取消斷言是本地合成 peer 的資源清理契約，不是 Canon 官方相機反應 SLA。
- 本次修正不被認定為過往亂對焦或 CPU-only 重連超時的根因。沒有實體相機、光學合焦、Wi-Fi／USB 硬體、其他機型 storage 路徑的新驗證。

新提交仍須通過 [PR #194 的精確 head checks](https://github.com/js051/open-eos-control/pull/194/checks)；上述 `b18cc0ed` 的完整 CI 不代替後續提交。最新 head、樹一致性、實際 outgoing 掃描與最終 CI 結果記錄於交付摘要。

## 功能差異與 Release Assessment

官方 Camera Connect 的平台／機型差異、操作流程與尚缺功能，繼續以[原對照報告](android-offline-reliability-2026-10-03.zh-TW.md#官方功能與流程對照)為準。本次沒有新增藍牙、自動傳圖、RAW 顯影，或變更正常快門 AF 語意。

- Baseline：v0.10.0 Development Preview。
- Impact：patch，修正既有取消與背景停止可靠性；未調整版號。
- 交付門檻：最終精確 head 的 CI；合併與發布仍是獨立決定。
- 實體裝置狀態：本輪未測，相關相容性與光學聲明仍受限制。
