# PC 漸進媒體列舉與取消

狀態：本地實作與 Node 自動測試通過。尚未執行瀏覽器或實機驗收；這不是 PR ready、已合併或已發布的聲明。

## 使用行為

PC 媒體庫在原本的 `GET /v1/session/{id}/media` 請求加入可選的版本化 Accept。支援新協定的 Bridge CCAPI 可在遍歷完成前顯示已完整收到的一批媒體；舊 Bridge 與不支援串流的引擎仍從同一次回應讀取原本的 JSON。沒有探測請求、第二次 fallback 遍歷或自動重送；列舉請求拒絕 HTTP redirect。

「取消載入」立即停止 PC 等待、保留最後一個完整收到的 partial snapshot，並顯示取消狀態。支援新協定的 Bridge 會合作停止後續相機頁面排程；目前已開始的相機讀取仍可能完成。舊 Bridge 或 gphoto2 的取消只保證停止客戶端等待，不能證明其後端遍歷已停止，更不代表實體相機 I/O 立即停止。

取消或失敗後，舊 contents 通知不會重新啟動這次遍歷。需要重試時可按「重新整理」；切換媒體範圍會建立新的請求 owner。之後真正收到的新 contents 通知仍可刷新媒體庫。連線被替換時，即使後端沿用相同 session ID，舊 callback、terminal 與清理動作也不能覆寫新連線結果。

## Partial、範圍與本地操作

載入中的數量只表示本次已收到並顯示的媒體，不表示全卡總數。Recent 保留原本的 61 個哨兵／最多顯示 60 個語義；Full card 不增加 500 或 1000 個的靜默截斷。只有正確的 terminal 與 EOF 都收到，才顯示這次所選瀏覽範圍完成。

串流 snapshot、瀏覽完成與 Recent 完成均不是原子相機快照或 strict inventory 證明，不能用來授權 foreground import baseline。Strict inventory 的既有流程與驗收條件保持獨立。

日期、評分、資料夾、排序與已選項目在 partial 更新後保留；尚未出現在該 snapshot 的選取不當作刪除。瀏覽器只呈現當前最多 72 個卡片。載入期間，本地篩選與選取不會觸發 thumbnail、info 或原始檔 fanout。成功完成後，才恢復目前卡片頁的縮圖需求。使用者明確開啟預覽、詳細資料或下載等相機操作時，先退休正在列舉的 owner。

## 輸入驗證

依[Bridge 協定](../desktop-bridge-protocol.md)，PC 驗證 request/session/scope/limit、連續 sequence/revision、offset/total/count、UTF-8、完整 snapshot 與唯一 terminal。每行上限 65,536 bytes，每塊最多 128 個，每個 snapshot 最多 100,000 個與 64 MiB 原始 frame bytes；達上限會明確失敗，不得截斷後假稱成功。錯誤或不完整下一版仍保留先前完整 snapshot。

普通 JSON 相容回應仍使用原本的 items 形狀，但媒體列舉解碼會拒絕重複的解碼後 JSON key、無法往返的未配對 Unicode surrogate、錯型或重複 ID，以及超過資源上限的資料。這是針對不明確／無效身分資料的刻意收斂；正常 Bridge 輸出不受影響。此規則只套用媒體列舉，不改動其他 API 的 JSON 接受規則。列舉的非 2xx 錯誤本文另限制在 64 KiB，避免繞過串流解析的資源上限。

## 自動驗證

執行 `npm --prefix bridge run test:modules`：34 個模組命令全部通過。其中 24 個 Node test-runner 檔案合計 874 tests，全部通過；另外 10 個沿用既有 assertion runner。兩個新增測試檔已加入正常 `test:modules`，合計 74 tests（56 parser、18 production owner）。原本 event recovery 測試也驗證取消／失敗不重播列舉，並保留 state snapshot／poll 的退避恢復。

涵蓋 partial 早於 terminal、跨 chunk UTF-8、1,101 個 full-card 項目、Recent61、64 MiB 上限、錯誤／缺少／額外 terminal、未完成 revision、重複 key／surrogate、HTTP redirect／錯誤本文上限、單一 JSON 相容回應、同 ID 重新連線、過期 callback、headers 前取消、terminal 後 EOF 前取消、事件等待與取消競態，以及本地選取／filter／72-card 縮圖排程。

另外直接以 Python `unittest.FunctionTestCase` 執行既有 `test_static_ui.py` 六個函式：6/6 通過。靜態 fixture 已對齊 owner-aware 的 call → await → outcome 關係、helper signature、Recent61 建構與完整 owner/session/scope/generation/abort guard；原有路徑、授權與憑證儲存安全 assertions 均保留。這是六個函式的直接執行，不是完整 pytest suite；沒有安裝 pytest。JavaScript 原始碼保持不變，因此此 fixture-only 修正未重跑已通過的 Node 批次。

`git diff --check` 通過。

NOT RUN：瀏覽器測試、瀏覽器視覺檢查、實體相機／USB 驗收、CI。本次沒有安裝、commit、push、合併或發布。Response stream、模擬 DOM 與 deferred callback 證據不能當作實體相機測試。
