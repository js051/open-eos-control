# iOS REC 指令結果未知：保留手動停止責任

## 範圍與基準

- 基準：PR #236 的既有 iOS REC ACK／readback、停止後影片檢視批次；本地父 commit `16c23c98cda3caa4be4b78745873773563896ac2`，tree `6a12be29`。
- 補上「相機已執行 Start，但指令回應遺失；之後 status 仍是舊 false」的責任缺口。實作、測試來源及本文是一個批次。
- 不合併 main、不發版、不推送、不觸發 CI、不安裝工具；不宣稱實體相機已驗證。原 ACK-only 錯誤與已完成的停止後影片路徑保留。

## 行為與證據邊界

1. `CameraRecordingCommandEvidence` 分開保存 `unconfirmed` 與 `acknowledged`。它是指令邊界證據，不是相機錄影觀察。Core 的 `CancellationError`／URLSession cancelled 保留原本傳遞契約，App 可向同一個 client 讀取已送出邊界的證據，不須把取消改寫為 ACK。
2. direct CCAPI 在 discovery、溫度、能力、request construction 與送出前取消檢查完成後，才在實際 REC transport 邊界標示未知。明確非 2xx 仍拋原 HTTP 錯誤。未收到 transport 結果、或 simulator 的 REC 回應無法解碼，各自保留結果未知；只有正常消費的成功回應才建立 ACK。canonical 的 recording boolean 仍是既有 ACK 快取，不稱為實體錄影狀態。
3. Bridge camera-hop 新代碼只在 REC request 上、HTTP 502、`RECORDING_COMMAND_UNCONFIRMED`、`VIDEO_RECORDING`、`ccapi` 完全匹配時轉為 typed unknown。相似代碼的其他 status／feature／engine 不採用該證據。HTTP 502 若缺少完整 error envelope（空白 body、invalid JSON、缺少 code／message），只能保留 UNKNOWN，不能把 fallback `HTTP_502` 當成相機已拒絕。完整已知拒絕在 cancellation 檢查之前清除本次 attempt 證據，再保留原取消傳遞；此操作不清除 App 先前已有的未知責任。Bridge-hop transport 斷線也只在完成 session／request 檢查並進入 send 後保留未知。`RECORDING_STATUS_READBACK_FAILED` 只有 HTTP 502 才能建立既有 ACK／readback 分類；401／403／500／503 的同名 code 仍是拒絕，取消亦不能把它提升為 ACK。REC 的 code／feature／engine 保留原字串精確比對，前後空白不能被 trim 成有效協定值。
4. Bridge REC 成功 body 必須是 object；recording 僅接受 JSON Boolean、null 或缺失，數字 0／1／2、字串、array／object 都是 UNKNOWN，不能產生假 false。Bridge status 與 simulator required status 的 recording 使用相同嚴格型別邊界，不改其他欄位的 generic parser；無效 status 不能清除 pending。Simulator 已收到 ACK 後遇到無效 required status，仍屬 ACK／readback failure，不能改成 UNKNOWN 或新 false。canonical complete 2xx／204 ACK 契約保留。
5. App 的 `recordingCommandRecovery` 與既有 `recordingReadbackRecovery` 分開。未知不寫入推測的 snapshot true／false，也不冒稱 ACK；它使目前連線維持錄影模式與 Stop 責任。Refresh／事件只讀 status，任何 true／false／nil 都不能清除此未知，包括 canonical 舊 false 快取。
6. 下一次使用者明確按 REC 只送 Stop。能力或溫度不允許 Start 時仍能停止。不自動重送 Start、不自動 Stop、不新增恢復輪詢。明確拒絕的 Stop 不清除先前未知；目前 Stop 正常返回或取得其 ACK 才能轉入既有 ACK／readback 流程。
7. 第一次明確 Stop 建立的影片檢視基準跨未知／拒絕重試保留。未知 Stop 的 false Refresh 不能啟動影片清單。只有目前 Stop 確認 stopped，或該 Stop 已 ACK 後的既有 readback 恢復，才開始原本有界最近影片檢視。
8. Disconnect 先完成上一個 client 的 close、阻止任何新的 REC admission，才採樣該 client 的 pending send 證據；同時保留 App 已知的 Stop 責任，顯示「上一台相機尚未確認停止錄影」警示。它不對上一台自動發 Stop，也不把該責任或指令目標移到新 session。使用者直接檢查上一台後可確認關閉警示；此操作不送出命令。新舊 session 與晚到回應仍由既有 generation 隔離。

## 測試來源

`RecordingReadbackClientTests` 新增十二個方法，合計十八個方法：

- synthetic peer 先獨立設定 recording，再丟失 Start 回應；canonical client 先由 Stop ACK 建立 false cache，之後讀回 false 仍保存未知證據，沒有自動 REC。
- Bridge ACK 的非 502 與取消負例、raw code／feature／engine 前後空白負例；REC 成功與 status 的 numeric／string／array／object 拒絕，true／false／null／missing 正例；simulator 兩方向的已 ACK 後 required status 型別錯誤。
- Bridge 新代碼精確的 status／feature／engine 正例與負例；相機先套用 REC 再收到空白／malformed／partial 502 error body，仍保留 UNKNOWN。
- 精確 temperature gate：較早 evidence read 為 nil 後先恢復 preflight、讓 Start admission 再 Close，post-close evidence 必須為 UNKNOWN；若先 Close 再恢復 preflight，必須零 REC。另驗收到完整拒絕與 caller cancellation 同時發生時仍拋 CancellationError、但不保留 UNKNOWN。
- direct sent-then-cancel 保留未知，送出前已取消則零 REC、零證據；既有 post-ACK cancellation 仍保留獨立 ACK。
- discovery／未初始化等 preflight 失敗沒有 REC attempt；明確 HTTP 拒絕沒有未知／ACK 或相機 side effect。

`RecordingReadbackRecoveryTests` 新增十三個方法，合計三十個方法：

- 真實 Core client 經 synthetic HTTP peer 的 applied-then-lost Start；獨立 camera state 為 true，而 App snapshot／refresh false，下一次只 Stop。
- Bridge typed camera-hop unknown、拒絕 Stop 與最初 Stop 影片基準；新影片之後進入媒體庫仍不改寫原基準。
- lost Stop、false Refresh 零影片清單、能力／溫度限制下仍可明確 Stop；Stop ACK/readback 可解決舊未知。
- 初次已知拒絕、sent-then-cancel、較早 Refresh 晚到、事件 stale false、零自動 REC。
- malformed REC recording flag 不得把已套用 Start 變成 false／再次 Start；Bridge／simulator 兩方向的 malformed status 必須保留原 pending、snapshot 與 owner。
- Bridge REC 先套用但 502 error body 遺失／損壞／不完整，Refresh false 後下一次仍只 Stop。完整拒絕搭配取消不會新建未知；已有未知則不被這次拒絕清除。
- disconnect 在指令仍待回覆時保留上一台警示；success／lost／typed unknown／cancelled 的晚到回應不能改寫新 camera 的 snapshot、錯誤或命令。
- 成功 Stop 後斷線不留下上一台錄影警示；原 ACK-only 及送出前取消斷言保留。

所有 peer 都是 synthetic fixture，沒有實體相機；不能把它們當成 Canon 物理狀態或 iPhone 執行證據。

## 實際 URLSession 的回應本文邊界

一般 `send` 原先以 shared URLSession 的 `data(for:)` 收完整 body；即使已收到 401／403／503，body 收取或完整長度驗證失敗也只會往外拋錯，丟失已知拒絕 status。直接把這個錯誤視為 UNKNOWN 會誤建 Stop 責任。

本批增加只供 REC 使用的 `sendRecordingCommand`：

- 自訂 transport 的預設實作仍只呼叫原 `send` 一次，不改原錯誤契約，也沒有 retry／fallback。
- URLSession override 對同一份 URLRequest 建立一次 data task，沿既有 stream handle 先取得 headers，再收有界 body。它回傳 received response、body completeness 與 cancellation；一般 `send`、下載與其他控制命令不改。
- direct CCAPI 已收到非 2xx 即保留已知拒絕；Bridge 的 401／403／503 等明確拒絕 status 亦可在本文遺失後保留。2xx body loss 及 Bridge 502 body loss 必須保持 UNKNOWN。取消仍經既有取消通道傳遞，但可先清除本次已收到的拒絕證據。
- REC body 在 delegate 入列前及收集端都限制為 32 KiB；完整回應仍跑既有 Content-Length／encoding integrity 規則。所有完成／失敗／取消路徑都關閉 stream，overflow 立即取消 task。handle 也保存 start 前的 cancellation，使已取消任務不會因 task 尚未安裝而被意外 resume。
- `send` 與既有 `openStream` 都使用原 URLRequest、沒有 App 層 retry loop，也都沒有自訂 redirect 禁令。此批不宣稱新增或既有的 no-redirect 保證。REC 使用 copied configuration 的每次 request session，和一般 send 的 shared session 不同；method、body、headers 不改，不另發 fallback request。

`URLSessionShutterWireTests` 增加四個 real loopback 方法的來源：兩種 REC client 各測 200／401／403／502／503 截斷 body，獨立 peer side effect、精確 POST path／body 與只收到一次 REC；完整 200／204、33 KiB 超限的 bounded response；已取消呼叫零 request；另以 internal 唯讀 header observer 精確等待 reader 已收到 200／502／503 headers，body 持續停住，再取消並要求同一 socket 在兩秒 expectation 內 EOF、一次完整 POST、bodyComplete=false、cancelled=true。fixture 沿用三秒 socket deadline，沒有 sleep、假裝收到 headers 或新 protocol 路徑。沿既有 socket fixture 擴充，不使用 URLProtocol 假裝線上傳輸，也沒有以來源存在宣稱測試已執行。

## 驗證狀態與後續

- 本地只有 diff 空白檢查、雙語鍵唯一性／一致性及逐路徑來源審查。它們不是 Swift 編譯或行為測試通過。
- **NOT_RUN**：Swift／Swift Core tests、Xcode App tests、UI tests、完整必要 CI。本 Linux 工作環境沒有 `swift` 或 `xcodebuild`。未安裝／下載工具，也未觸發另一個微小 CI。
- **NOT_RUN**：iPhone／實體 EOS 相機、camera-hop 與 Bridge-hop 實際斷線驗收。
- 下一個完整批次須執行 Core 的 `RecordingReadbackClientTests`、App 的 `RecordingReadbackRecoveryTests`、`CaptureMediaJourneyTests`、`ControlResponseOwnershipTests`、`URLSessionShutterWireTests` 的 REC transport 截斷回應與上限控制、既有必要 aggregate 及雙語 REC／影片原檔 UI 流程，結果綁定最終 head SHA。沒有這些結果之前只能稱為本地 implemented，不能稱 PR ready。

### 2026-10-13 首輪完整分類 CI 與 body gate 修正候選

原 head `c7f768a09277d9d4773f473de71269181d7a1f55`、run `38374172415` 的 Core job `115218341099` 在 09:09:50 UTC 失敗：348 個 tests 中，同一個 `testRecordingReaderCancellationAfterReceivedHeadersClosesPendingBodyWithoutReplay` 留下兩個 failure（其中一個 unexpected）。第一個是兩秒內未收到真實 header observer；隨後取消得到 URLSession -999。這個失敗保留，不能稱 Core 全綠。`RecordingReadbackClientTests` 的 18 個 tests 已通過，亦不能取代該失敗。

最小候選只修 loopback fixture：回應明確宣告 `application/json`，送出兩個 bytes `{}`，但 Content-Length 仍是 64，剩餘 62 bytes 持續停住。測試仍必須觀察真實 client headers 才進入取消，並要求 bodyComplete=false、cancelled=true、socket EOF/RESET 及一次完整 POST。取消可能先於或晚於資料 callback，因此只接受原 prefix 的 0–2 bytes，逐 byte 核對。兩秒 expectations 與三秒 socket deadline 未增加。

同一原 head 的 App job `115218341297` 已在 09:36:21 UTC 回報 TEST SUCCEEDED：App 414／414、UI 38／38；双語 REC→新影片原檔→分享→關閉→返回旅程在 09:22:35 通過。這些通過不消除 Core 的失敗，也不算本次 fixture 新候選的驗證。

純 headers 為何未及時送達 observer 尚未確認；這是建立「headers 已到、body 尚未完成」受控狀態的 fixture 候選，不是 production 根因修復。獨立 source review 無 blocking finding；修後 Swift runtime **NOT_RUN**，待同批完整 CI。實體相機／iPhone 仍未驗。

## Release Assessment

- 最新遠端 release：本工作不查遠端，整合時沿用父工作的已驗證基準。
- 建議影響：patch；修正既有錄影控制在遺失回應後可能再次 Start 的責任缺口，保持協定相容的 additive error code。
- 發布阻擋：最終完整批次的 Swift／App／UI／必要 CI 尚未執行；此批不作發版決定。
- 實體裝置狀態：NOT_RUN，不新增相容性或可靠度聲明。
