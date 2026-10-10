# iOS RTP 排隊回覆與原始 session 歸屬

基底為已驗收 main `4739880764bd2e070c6fdbce59841d30a2b7edfc`，PR234 main acceptance `38038177923`。目前是本地候選，Swift／XCTest 尚未執行；不得將來源反例描述為 runtime 已重現。

## 問題與修正邊界

Audio producer 原本已有 active session 檢查，但取得 status snapshot 後發出的 event 不含身分；AppState 再將它排入 MainActor Task。舊 A 回覆可在 B 或 disconnect 之後消費。Video receive 同樣把原影格排到 MainActor，而 enqueue 只有 rendering/displayLayer 條件。

本批讓 event 帶入原始 owner，producer 發送與 AppState 消費都核對該身分。Audio snapshot 與 owner 在同一把 lock 下取得，不能把 A 資料重新標成 B。disconnect 在 MainActor 同步退休 owner，清除音訊狀態與 renderer session；原 close 仍會清理原資源。Current session close 產生新的 inactive owner，因此停止狀態仍能送達；舊 A close 不會退休 B。

Video 排隊閉包攜帶原 sessionID，enqueue 先驗其仍是目前 session。更換 renderer session 時清除舊 SPS/PPS/format，避免新連線沿用舊解碼狀態。預設仍使用 AVSampleBufferDisplayLayer；測試的可注入 consumer 只記錄接收到的 timestamp，不播放或解碼影片。

## 受控驗證

七個測試方法準備以下順序：

1. 持有 A audio delivery，先套用 B 再交付 A，應保留 B。
2. 持有 A delivery，disconnect 後才交付 A，應維持 inactive。
3. Current delivery 與 current close inactive 的正例。
4. A close 的 inactive 已排隊，B 啟動後才交付舊 inactive，應保留 B。
5. A queued video 在 B 之後消費不能送入 renderer；A close 不影響 B，B close 後其影格不能再送入。
6. 同步退休後拒絕舊影格，同 SDP 新 session 仍可呈現，rendering enable/disable 正例保留。
7. 無 `m=audio` 的 video-only SDP，current video 仍可送入 consumer，退休後拒絕。

測試沿用真 controller `makeSession` 與合成 SDP，沒有 bind/start listener、相機連線或 playback 啟動。原音訊 stop 清理可能呼叫平台 audio-session deactivate；未宣稱未接觸任何平台音訊 API。XCTest teardown 會等待所有 allocated session close、清除控制佇列及隔離 UserDefaults。沒有用任意 sleep 推論完成。

最初只有 dispatcher seam 與三個預期反例的來源保留於本地；沒有刻意啟動一輪紅 CI。既有 close-replaced-session 測試只隨 event envelope 解包介面調整，原 assertions 保留。

## 驗收與 Release Assessment

Whitespace 與版本一致性靜態檢查通過。完整 production/test source 已由獨立 reviewer 讀審、無阻擋项，並依其要求補 video-only 正例；正常 exact-head iOS App/UI CI 尚待完成，不能沿用 PR234 綠燈。

基準 v0.12.0 Development Preview，impact patch。Release HOLD 保留；無版本、tag、發版、KVM 或必要 CI gate 修改。合成 session 不能證明實體 Canon／iPhone RTP 相容性；PR219 Compose cause UNKNOWN 與 PR223 驗收限制不因本批而解除。
