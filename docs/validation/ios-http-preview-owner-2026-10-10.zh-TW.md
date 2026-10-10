# iOS 圖片預覽的 HTTP 取消所有權

## 來源與反例

基準為已接受 main `cf81aab41cc87d1e0c183141fe63e6ee24c84705`。PC #228、Android #229 的圖片請求取消已分別驗收；本批只處理 iOS 現有圖片預覽入口。

`MediaView` 以未保存的 Task 呼叫 `openMediaPreview`；後者直接等待 `session.mediaPreview`。`resetMediaPreview` 退休 token、清畫面及釋放 MEDIA，但沒有可取消的 Task handle。因此 Close、scope 切換、disconnect 或 offline 重設後，舊 HTTP read 仍可能繼續；新預覽／metadata／原檔操作已可進入。原 token／generation guard 能排除遲到結果，但不會停止讀取。

這是可由生產來源直接證明的取消接線缺口。保存的 bounded source assertions 確認基準沒有 reset cancellation；沒有將它稱為 Swift runtime 紅測試。兩個現有 client 的 image representation 都透過 `CameraHTTPTransport.send`；生產 URLSession 實作使用支援 Task cancellation 的 `session.data(for:)`。

## 修正邊界

- 只保存 image read 的子 Task；在 viewer state 發布前設好 handle。Task 進入及回傳後均檢查取消。
- reset 只取消捕捉到的 image Task，並沿用立即退休 token／MEDIA 的契約。取消請求不保證遠端相機或 Bridge upstream 已停止。
- caller Task 取消透過 cancellation handler 傳給同一 image Task。取消結果不成為全域錯誤；正常未取消的錯誤仍可見。
- 舊 completion 只有仍持有同 token／session generation 才能清自己的 handle／loading／MEDIA，不碰新預覽或原檔 Task。
- 影片配置仍留在原 caller，遲到的 playback stream 繼續 close。不能取消共用整個 viewer Task：Bridge POST 可能已配置 ticket，而 requestJSON 在交回 ticket 前檢查取消，會使既有 late-ticket 清理失去 URL。
- 不修改原檔下載、session 全域取消、相機命令、依賴版本、timeout、必要 CI 或權限。

## 驗收狀態

本機沒有 Swift runtime；新增 Swift tests 尚未編譯／執行。來源檢查與獨立 review 不能代替 macOS App/Core/UI 的正常 exact-head CI。既有非合作式 gate、舊回應隔離、重開相同項目、跨 session、原檔 bytes／分享及 video cleanup 斷言均應保留。

`CaptureMediaJourneyTests` 保留原 27 個方法，新增 9 個：四種 reset、caller 在已進入及尚未進入時取消、舊 preview 返回不影響新原檔、已完成 viewer 關閉不影響原檔與分享、影片遲到 ticket 恰好一次 DELETE 且不啟動播放／取消新 image。fixture 以 thread-safe recorder 觀察取消；原 continuation gate 仍故意不因取消而完成，避免把舊回應隔離測試改成合作式捷徑。bounded wait 的成功條件是實際 cancellation record，不是經過固定時間。

PR 的最後驗收摘要將記錄實際 CI、head/tree 與 main provenance；本文保留交付前狀態。沒有新增真相機、實體 iPhone 或 network timing 證據。

## Release Assessment

- 已發布基線：v0.12.0 Development Preview。
- 建議影響：patch，補齊已存在圖片預覽的取消行為。
- 本批不改版本、不建立 release candidate、不發布。
- PR219 根因 UNKNOWN、PR223 普通 App 驗收的 KVM 限制及 frozen v0.13 HOLD 仍各自保留；本批全綠不會自動解除它們。
