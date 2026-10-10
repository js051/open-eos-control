# iOS 媒體操作的連線與清單回應歸屬

## 基準與已確認來源缺口

基準是 PR230 已接受 main `2c86633fb2f98a82bd4ff78637998dc94607e63a`。本批接續既有相簿操作的可靠性；沒有新增相機功能。

獨立來源稽核確認以下路徑：

- `loadMediaInfo`、四個 metadata 寫入的共用 helper、`deleteMedia` 在 await 後直接更新資料／錯誤，並無條件釋放 MEDIA。disconnect/reconnect 後的舊結果能影響替代連線的同 ID 項目或 busy。
- Bridge client 通常會把舊 JSON／DELETE 回應轉成 sessionChanged，因此 Bridge 反例主要是舊錯誤與 busy 清理；direct CCAPI 另有成功 payload 能進入 AppState。兩者不能混稱同一種實際結果。
- upload 只在第一個回應後部分核 token；後續 listing 與取消後 detached reconciliation 缺少完整歸屬。替代連線可能被舊 loading／failed／完成結果、媒體清單及 viewer reset 污染。
- queued upload 在開始執行時才讀目前 session。生產 URLSession 對已取消 task 會在 resume 前取消，所以不能聲稱已證明向替代相機送出 POST；但舊 worker 能捕捉替代 session，接著在取消分支啟動未取消的 detached 補讀。
- scope picker 在 upload 期間可操作。舊 scope 的補讀不能覆蓋新 scope，即使切換後又回到同一個值。

以上是來源接線與控制流程證據；本機沒有 Swift，沒有宣稱已執行基準紅測試或觀察到物理裝置上的發生頻率。

## 修改契約

1. Info、metadata 與 delete 在取得 MEDIA 時保存 session generation；成功、錯誤與 finally 只作用於原 generation。已發送的相機寫入不重送、不補償刪除、不新增取消命令。
2. Upload admission 同步捕捉原 session、token、generation。worker 入場先核 ownership 與取消，退休／尚未發送即取消的工作不送 upload 或 reconciliation。
3. Progress、回應、後續 listing、錯誤、清單狀態、能力觀察與 finally 均保留原 upload owner。舊工作不能清掉新工作。
4. 同 owner 的使用者取消仍可對原 Bridge session 只讀查證已完成上傳。退休 owner 在 detached read 前及返回後都停止 UI publication；不改成重傳。
5. Listing publication 同時核對 scope 與 media-library generation。每次 scope 改變都退休前一代；舊 listing 不重設新清單／viewer。正常未取消 completion 已確認的 upload ACK 名稱仍可顯示，不把清單刷新當上傳本身。取消分支保留既有規則：須由只讀查證找到匹配項目才顯示完成；即使曾收到 ACK，查證失敗仍不新增完成名稱。
6. 接受 upload 後，Task closure 以 defer 持有既有 security-scoped URL 的清理責任，包含 weak self 消失或 stale entry。拒絕 admission 時仍由原 picker 呼叫端清理；沒有新增存取權限。`mediaUploadTask` 僅比照既有 download Task 開放 internal read-only observation，讓測試等待精確工作終態。

## 驗收與限制

新增 deterministic fixture 須分清 direct CCAPI 成功與 Bridge close rejection，覆蓋替代連線／同 ID、原檔與 viewer 保存、upload POST／listing／取消查證各等待點、queued entry 及 scope A→B→A。非合作式舊回應不能以提前 throw 取代真正 ownership 斷言。

已加入 `MediaResponseOwnershipTests` 的 8 個參數化方法，保留全部既有測試。各等待點使用 actor gate、精確 old/new Task completion 及有界狀態觀測，不以 sleep 作證。Bridge 舊 listing 的成功通常會先變成 sessionChanged，所以相關案例驗證舊錯誤／failed 狀態被抑制；真正成功 payload 對 App 的遲到污染由 direct CCAPI info／metadata／delete 另行覆蓋。Pre-entry 案例證明取消或退休後零 POST／補讀，但未在 queued worker 首次執行前安裝一個完整連上的替代 session；該更窄排程情境仍只有來源 guard 證據。

Swift 編譯與 runtime 尚未本機執行；正常 macOS exact-head App/UI CI 是必要 gate。獨立 review、source assertions、version／whitespace 與 secret scan 只各代表自己的檢查，不代替 runtime。最後 PR 摘要記錄實際結果與 main provenance。

Release Assessment：已發布基線 v0.12.0 Development Preview；建議 patch，修復既有媒體工作跨連線／scope 的錯誤歸屬。沒有新增實體相機、iPhone 或 SAF/security-scoped provider 驗證；來源上的 exactly-once cleanup 不等於已測所有 provider。PR219 UNKNOWN、PR223 KVM 限制與 frozen v0.13 HOLD 保留，本批不改版本／發版／權限。
