# G03 設定 UI owner 退役（2026-10-16）

基線共同 dev `ad512c452edb7b83fe5587ed9ce510db95781232`。本群修補 PC Bridge 現有設定及檔名控制的 rendered-owner 邊界，沒有新增 backend 能力。

## 真反例與範圍

production reset、advanced render、modal editor、實際 input/apply handlers、updateSetting/runCaptureConfiguration 與既有 policy 以 Node 執行；合成 DOM／transport，不接相機。基線 28 案 3 pass／25 fail，都是產品斷言失敗，沒有 ReferenceError。真缺口：

- session reset 未關設定 modal／清 advanced controls。
- 舊 text/select/range input 與 Apply 能污染新 owner draft，或向新 session 送 POST。
- 舊 filename input/mode/Apply 能污染 draft 或送 PUT。
- 相同字串 session ID、不同 ID、同 session 重畫退役的 callback 均涵蓋。

保存 listener 再投遞用來模擬 queued callback，不宣稱真瀏覽器自動 blur 行為。初始反例 activeElement 為 null；不能把 focused input 隱藏後是否仍 focus 當成已復現問題。既有 modal editor generation、合法同 owner typing／draft preservation 基線已通過。

## 修正

reset 撤銷 advanced rendered-owner、清 DOM，關閉仍開啟的設定 modal。每次 advanced render 具有 session-object＋render-owner 身分；同 ID 重新連線也不同 owner。draft/input/apply handler 必須仍屬當前 rendered-owner；檔名子控制沿用相同 owner。相鄰 clock/directory/maintenance 入口一併拒絕同一份退役 DOM 的事件，保留原確認與能力 gate。

focused text 值只在同 session 的合法重畫中保留；沒有刪掉 typing 或既有 draft revision／consume/outcome 契約。新 session 或新 render 退役舊回呼。此 guard 不取代 backend/operation late-response owner。

## 三端邊界

iOS `CameraSheets.swift` 的 More settings 在 mediaLibrarySessionID 變更時更換 settingEditorOwner，清 text/choice/range drafts 與 revisions；`CameraAppState` 對 operation 採 sessionGeneration＋connectionConfigurationOwner。Android `AdvancedSettingRow` 以 info＋mediaSessionGeneration 建立 ShootingSettingOwner，draft/token/revision remember(owner,key)，提交攜帶該 owner。這些是 source 對照，未執行 mobile native。

產品封存當時尚未實看；本群後續新 HEAD Firefox 結果見下節，前批 media reconnect 不作本群替代證據。實體相機、native、CI 均 NOT_RUN。Release assessment：patch；沒有 main/release 或可下載成品。

## 獨立 review 補出的相鄰路徑

第一版最小修正讓初始 28 案通過，但獨立 review 找到兩條原測試未涵蓋的真路徑：core exposure button 的舊 closure 可以開舊設定 modal；browser 的 close event 是排程事件，reset 關閉舊 modal 後若新 editor 已開啟，舊 close handler 會拿當下 editor 退休，甚至取消新操作。

補上真 renderExposure 與 production close listener 後，第二輪 46 案 42 pass／4 fail（3 個 exposure owner、1 個 late-close）。修正 exposure rendered-owner，reset 清 strip；所有程式 close 入口經同一 helper 保存關閉 editor，close event 僅退休對應 editor，舊事件不能影響新 modal。Escape cancel 沿同一路徑，保留正常取消行為。沒有以 stub 掉相鄰 renderer／close listener 取得綠燈。

合成 DOM 明確排程 close event，可以控制其晚到時點；這證明 production 回呼的隔離，不冒稱已跑 Firefox 真事件序列。合法輸入／當前送出、空 session、same/different ID、重畫、maintenance 確認入口亦有正反控制。

## 同源最終回歸

9 份 Node 檔案共 **813 pass／0 fail／0 cancelled／0 skipped**，729.41048 ms；其中新 UI 退役矩陣 46 案。測前後 source manifest 一致。app.js SHA256 `bd0eea03f5e23f094d4b3d0d51ee4f8782984e905a65ebce7f8a14f8a48cdb2e`。相鄰 shooting-settings、capture-configuration、maintenance、media reconnect 與 connection-guidance 回歸保留所有有效斷言；沒有以 source-only mobile 核對取代原生測試。

獨立只讀 review 最終未見新增 blocking；已復核 exposure owner 與 queued close 修正及對應反例。reviewer 未親跑測試，813 結果來自 owner 本地執行。

## exact HEAD 原生 Firefox 實看

產品固定 `6362b87edbf7b369d39b4427b1a62b9211cfc773`；2026-10-16 13:05:08.592 啟動、13:05:09.522 ready。沿既有雲端 native Firefox157.0.1、新 profile、正常 sandbox，純 FakeRunner USB（沒有硬體 USB／相機）。loopback 18765／18766，CCAPI origin restriction、EDSDK disabled、RTP disabled；本輪沒有操作 CCAPI。

- ISO400 選800後按原生 Escape，modal 關閉且 FakeRunner 零寫入。重開保留合法草稿，Apply後 modal 正常關閉、ISO顯示800，只有一次對應 set-config。
- Artist 輸入 `unsent_draft`，Apply啟用；普通 Refresh 重畫後保留草稿。沒有提交該值。
- 斷線／重連同 fake device（無 reload）後 Artist 回原 `Jason`，舊草稿不殘留；新輸入 `fresh_artist` 並 Apply成功。
- 終態 FakeRunner 全 command核對只有兩次寫入：ISO=800、artist=fresh_artist，未出現 unsent_draft 寫入。文字 Jason 是既有公開測試fixture值，不是使用者資料。

六張原始 CUA JPEG 持久保存：[Escape未寫](assets/settings-owner-20261016/01-escape-no-write.jpg)、[ISO完成](assets/settings-owner-20261016/02-iso-applied.jpg)、[未送草稿](assets/settings-owner-20261016/03-artist-unsent.jpg)、[Refresh保留](assets/settings-owner-20261016/04-refresh-keeps-draft.jpg)、[重連清除](assets/settings-owner-20261016/05-reconnect-clears-draft.jpg)、[新Artist完成](assets/settings-owner-20261016/06-new-artist-applied.jpg)。來源、fixture、終態及影像hash見[證據目錄](assets/settings-owner-20261016/)。

13:09:35.732 主動停止 owned groups，exit -15／-15；不是自然 exit0。原即時收據仍列兩個退出中的children，保留不改；13:09:42 ps與13:10:28 /proc重核皆無owned processes。已清 disposable profile 40,885,534 logical bytes，free 5,647,679,488 → 5,688,909,824 bytes；監測最低free5,650,317,312，未碰4GiB底線，非hard quota。

這輪證明正常 typing、Escape、合法Apply與重連清draft。沒有在人為UI流程強制觸發所有 queued old-event race，也沒有量測 Refresh過程的caret/focus連續性；那些精準owner反例仍以Node為證據。不能稱Chromium、mobile native、真機或全功能驗收。server-receipt的NOT_YET_RUN為啟動時原狀態，終態見post-stop-observations。
