# pending Apply 取消後的鍵盤焦點

基準 dev `141dca33`。原 G03/#42/#43、G13/#122，延續已修復的 Escape 同步退休與成功 Apply 焦點，不新增設定能力。

## 已重現

Firefox `4faea09a` 在 pending Apply→Escape→late matched stream 後，ISO仍100，表示資料退休修正有效；但ISO按鈕在關窗時仍disabled，response finally重繪後focus掉BODY，Enter無法重開。footer也仍顯示Working。基準圖與telemetry已保存於pc-setting-escape-2026-10-17/fixed，不能把那張03命名當成重開成功。

## 最小修正

只在關閉自己的pending editor時，把closing focus owner存於該設定record。該record的finally完成、解除自己的busy並重繪後，僅精確session/editor/refresh generation、live view、沒有新dialog或新焦點目的地時，從BODY回到目前同key可用按鈕。舊session、新editor、新operation、其他dialog、隱藏control與其他view均不恢復；只消費一次該record的return target。已有非BODY焦點時不搶回。

取消當下改用既有本地取消說明，取代殘留Working；不宣稱相機撤銷write。late receipt仍只保留歷史，不publish、不消耗draft，不自動重送。

## Host證據

production caller＋合成DOM新增8案：焦點基準79 pass/1 fail；加回覆文案反例後80 pass/1 fail；最終81/81。保留舊測試中使用者已轉移focus的control，以及新session/editor/generation/dialog/view/hidden六類隔離。

## 20:32 UTC Firefox 固定版對照

產品 source `3987b5f32b0f8c18e55712ffdacb87be78c1173b`，與完整 modules 3799/3799、static UI 15/15 測試的 bridge tree 相同（`ff1d8b08f3af0fdf19e43a2064174dea1ae83416`）。

既有雲端 Firefox、production HTML/JS、合成 stream receipt：pending ISO200 Apply 時當前 ISO100；Escape 後釋放 matched stream，當前值仍100，焦點回目前 BUTTON/iso，文案為「Cancelled waiting; camera result may still arrive」。直接 Enter 重開 dialog，200 draft 保留。3 張圖、事件、source/hash、runtime 收據保存在 [evidence](evidence/pc-setting-pending-focus-2026-10-17/assessment.json)。20:32:37 terminal、owned Firefox exit -15。

這是修正前 BODY／不能重開的同路徑對照；不代表 screen reader、native 或真相機結果。
