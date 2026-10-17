# PC 新媒體操作退休先前上傳的 UI owner

原 ledger G09/G10：#76、#78。延續既有媒體功能，不新增傳輸能力。

## 確認的缺陷

原 `downloadMedia` 接受新單檔下載時未推進 `refreshGeneration`；batch download 與 upload 已有此邊界。取消的 upload 仍可接收原 attempt 的有效遲來 receipt，這份證據應保留，但不能在新互動期間仍更新全域 UI。

production-function fixture 重現：開始 upload→取消→開始單檔下載並暫停原生 picker Promise→原 upload 有效 receipt 回來。基準中 operationState 從 preparingDownload 變 ready，下載仍 preparing，且舊 completion 額外開始一次媒體 refresh。

## 窄修正

單檔下載 admission 通過後、picker 之前推進既有 refreshGeneration。舊 upload receipt 仍記錄在原 attempt；既有 ownsMediaUpload 擋住舊 operationState、toast、refresh 與 renderer publication。沒有清除有效receipt或把取消誤寫成rollback。

新增四案是 picker-pending／download-settled × picker-cancelled／history-admission-failed：原source四案失敗，修正後通過。確認 picker取消與歷史持久化admission失敗仍有各自正確狀態，且不讀 original。既有取消後、尚未開始新互動的 late same-operation valid receipt 仍可確認；退休session的證據留存亦未改。

受影響 upload owner／contract、capture-review、media-refresh-preview、media-batch-owner共715/715 PASS。這些是 production函式＋合成transport/DOM/picker adapter，沒有真OS picker、相機upload/download或browser GUI race驗收。

## 審查與 Release assessment

另檢查過的 resetSession upload順序候選未列產品缺陷：正常disconnect已先cancel，maintenance admission排除傳輸，沒有證明其可達。Batch durable admission、close checkpoint、continuation明確binding／item membership／unique family、delete當前owner/token消耗未發現另一確證缺口。

本地baseline 0.13.0 Development Preview；impact patch。沒有版本bump、main合併或發布；Android/iOS native與實機仍未驗。

來源commit `8e7b992076a78b878d47b28f862ab0b618d56a17`，整合 `6153463ba2bf9e2a9f8cc8195c3ee814f78c6474`；正常pre-commit gitleaks掃描2.83KB，無洩漏。基準／修正／鄰接log已保存於integration `.codex/validation/eos-single-download-{baseline,fixed,adjacent}.log`。


## 同類三個 admission 的一次補齊

同一 production-function 重現亦證實 metadata mutation、delete、foreground auto-import admission 後的遲來 upload receipt 可改Ready並額外刷新。只在各自 admission 通過後推進generation：mutation有有效非空roster、delete明確確認且仍同session、auto-import符合前景/能力/picker條件。空roster、拒絕確認或不合資格不退休原attempt的publication。

新增9案：基準6個已接受操作案失敗、3個未接受操作案通過；修正9/9通過。Combined upload/mutation/delete/auto-import/capture-review/refresh/batch-owner共1553/1553 PASS。來源 `91a83537a7b63fcf9f077c13e24e9712c8d6bdd0`，整合 `f78efaedb735c0f76e984c7968533bba133924b9`；正常hook掃描4.26KB，無洩漏。

既有delete設計明確允許同session其他互動／refresh，並禁止新增global lock；本修正只退休舊upload UI publication，沒有把delete擴成全域鎖。此差異由既有media-delete-ownership contract核對。

## 上傳完成的可選 refresh 保留明確預覽

另一已重現的只讀流程：取消upload後開啟新photo preview，舊有效receipt被記錄且允許呈現完成結果；其optional refresh原本呼叫不保留preview的預設路徑，會關掉新預覽。進行中的upload也允許開preview，故不能在只讀openMediaPreview任意推進generation，否則會退休仍需完成cleanup的upload。

修正只把upload completion的可選refresh傳入`preservePreview: true`。新增3個production-function案例：active upload completion、cancelled late receipt、no-preview正常refresh。基準前兩案失敗、控制案通過；修正3/3，包含新listing未列出目前reviewed item仍保留preview owner/generation/image，upload狀態正常settle、listing完成。相關1556/1556 PASS。

來源 `529e708c6d52341e0f54db16fb3744ef81608f3a`，整合 `abbcde3bd247db8ee277fafe8f34edc2564168b6`；normal hook掃描3.08KB無洩漏。沒有將只讀preview變為全域互動鎖或取消原上傳。
