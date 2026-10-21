# PC 本機預覽啟動期間的 ended ownership

## 來源與目前驗證狀態

- 原始基準為 `5aee087b681fc592d9b0b9f88a42b51e188448dc`。本批對應原功能表 row 123（PC local UVC/HDMI preview input），不新增來源、權限或相機控制。
- 2026-10-21 03:29:37 UTC，單一離線 gate 已完成：原碼 baseline 精確三個 assertion RED，其餘 20 PASS；candidate focused **34／34 PASS**，原 helper 與未改完整 modules 指令 PASS。新 Desktop／Windows CI 仍待本批交付後執行。
- Release Assessment：patch。只改 `app.js`、既有 ownership test file 與本文件，沒有 workflow／版本／main／release 變更。

## 可達的來源缺口與證據界線

原 `startLocalVideo` 接受 stream 後先等待 `video.play()`；等 promise 成功才設 `localVideoActive=true` 並註冊 `ended` listener。
因此在保持同一 generation 的受控 interleaving 中，若 track 已經 ended，或在等待 playback 時 ended，較晚成功的 play 可以略過該終止狀態。
listener 不會替剛註冊的 callback 重播已發生的事件；原碼也沒有檢查 `track.readyState`。

後續原碼會排 frame callback、顯示 started 回饋、讓 local video 可見並將按鈕標成 Stop。
同時 diagnostics 的 public `trackState` 可以是 `ended`，與 active 狀態矛盾。
若 play 在 track ended 後才拒絕，原碼只得到一般 playback failure，無法保留已觀察的來源終止結果。

這是已執行的合成受控順序 source-level counterexample，不是已觀察到真 UVC/HDMI 拔除的 browser 故障。
不推論所有瀏覽器在 track 結束時如何 settle `play()`，也不聲稱已測實體 driver、裝置權限或畫面內容。
使用的 `readyState` 是產品現已讀取的公開 track 屬性；already-ended 案例只提供此值，不虛構 event delivery。

既有 generation fence、Stop/disconnect 對晚到 stream 的釋放及可見播放後的 ended cleanup 都保留。
原 browser journey 已涵蓋 Disconnect 後晚到 getUserMedia、一般 device/source replacement、播放拒絕和可見播放後 ended；不把這些舊證據重算成新缺口。
本機視訊是不同 preview producer，不表示可以在 camera control session 退休後繼續持有 track。

## 最小 ownership 修正

1. 取得且確認目前 opening 的 stream 後，在等待 `play()` 前註冊該 generation 的 ended listener。
2. 讀取到 `readyState === "ended"` 時，直接使用原 ended cleanup；在 await 前與成功後皆檢查，不等待另一個 event。
3. 原 ended handler 除了 active input，也可以退休該 generation 已持有、仍在 opening 的 stream。沒有目前 stream 且未 active 時仍不動作。
4. Cleanup 仍經原 `stopLocalVideo`：先增加 generation、取消 frame callback、停止 tracks、清除 srcObject／stream／track／busy／active。
5. 已退休 opening 的晚到 resolve/reject 仍走原 stale-generation 分支，不能重新宣布 started、覆蓋錯誤，或清掉 replacement stream/frame owner。
6. 不改 getUserMedia constraints、browser permission 流程、camera command、來源選擇 gate、session teardown 或自動重啟規則。沒有新 timer、retry、延遲或全域 holder。

## 凍結案例與實際合成紅綠結果

新增六個 named cases，完整使用 production `startLocalVideo`、`stopLocalVideo`、`handleLocalVideoEnded`、error classification、frame scheduling/cancellation 與原 `local-video.js` helper。
只有 browser stream/track/play promise 是合成物件。事件在當下交付給已註冊 listener，不因之後註冊而重播。
既有 ownership 檔案的原始 bytes 完整保留為 prefix，原 17 個 named cases 不修改。

| 新案例 | 原碼預期與實際結果 |
|---|---|
| startup ended track already acquired cannot publish started | `UVC_STARTUP_ALREADY_ENDED` assertion RED |
| startup ended during pending play cannot publish after resolve | `UVC_STARTUP_ENDED_RESOLVE` assertion RED |
| startup ended during pending play remains terminal after rejection | `UVC_STARTUP_ENDED_REJECT` assertion RED |
| startup live track starts normally and later ended retires it | PASS |
| startup retired play resolve and old ended cannot stop replacement | PASS |
| startup retired play rejection and old ended cannot overwrite replacement | PASS |

Baseline 實際執行完整 23-case ownership file：原 17 controls 加新六例，精確只有上述三個 assertion failures，其餘 20 PASS。
這同時保留原例，不以 test-name filter 排除案例，也不以編譯／fixture／setup 失敗冒充 RED。
Candidate focused scope 為 ownership 23 + 原 frame-freshness 11 = **34 named cases，全部 PASS**，所有原 cases 保留。
另執行原 `local-video.test.js` 並通過：它是含 32 個 assertion invocations 的單一 script，不算 32 個獨立 test cases。
最後執行完全未改的 `npm run test:modules`，66 個原 entry commands 全部 exit 0。完整輸出包含 56 個 named-test summaries，合計 3,904 tests／3,904 pass，fail／cancelled／skipped／todo 均為零；其餘 direct-assert scripts 不杜撰獨立案例數。不把 focused 與 full command 的重複案例相加成新覆蓋率。

單一 gate 實際總計 21.060 秒，沒有 setup 重試或預算延長。App／test syntax、23-case baseline、34-case candidate、helper、完整 modules 依序完成；完整 modules 佔 20.075 秒。最高取樣 process-tree RSS 86,052 KiB，最低取樣共享 free space 2,515,275,776 bytes。全部 160 個 module closure input hashes、baseline blob、原 ownership prefix 與完整 package script 在 gate 後仍一致。

## 執行與平台界線

預算為一次最多五分鐘離線 gate，含 syntax、baseline/candidate、原完整 modules、必要診斷與封存；2 CPU、取樣 process-tree RSS 512 MiB、共享磁碟至少 2 GiB 加 32 MiB stage reserve。
RSS 是監測停止線，不是 kernel hard cap。只用既有 Node/npm，不下載、不安裝、不開 browser、camera 或外部網路服務。原完整 modules 指令內的兩個既有 fixture 會綁定 ephemeral `127.0.0.1` HTTP server；保持原 bind 行為，若環境拒絕即保留失敗，不用旗標／權限／其他路徑繞過。
Baseline app/helper blobs 與 candidate tests、原測試、完整 package script 均事前凍結 hash；TAP reporter 明確指定，核對 exact method names、三個 assertion markers 與取消／跳過為零。

本地 fixture 驗證來源 ownership 及 callback 順序，不取代 DOM、實體 UVC/HDMI、driver 或真實 permission prompt。
正常 dev push 在無 open head:dev PR 時只匹配既有 Desktop 與 Windows workflows，約 12–20 分鐘加排隊。
既有 browser journey與必要檢查不改；沒有 Apple、Android host、emulator、KVM 或 workflow 變更。
發布前重新確認 dev SHA、沒有 open dev PR、正常 hooks、已選 workflow 沒有未完成 run 與實際 path fanout，完成後核對 remote tree 和 terminal receipt。
