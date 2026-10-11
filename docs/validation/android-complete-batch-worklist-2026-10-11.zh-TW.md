# Android 完整批次工作清單來源候選（2026-10-11）

基底：accepted main `089e71a3fb3249164d36173b04b682c5612d3966`。這批接續原 P2「單張／批次下載、取消及重新啟動後知道進度」；不是整體 P2 完成或集中 App 驗收通過。

## 行為

- 使用者原本選取的完整有序清單，在任何目的檔開啟或原檔 GET 前一次原子保存。每列使用本地隨機 ID，名稱只供顯示，不作相機來源身分。
- 每列必須先確認 durable admission 才進入原下載流程； admission 結果未知時停止後續 I/O，不把未開始項目記為下載失敗。
- 冷讀保留全部列，例如 5 完成、1 未確認、44 排隊。排隊只代表尚未有開始紀錄；不自動重連、續傳、重試或憑名稱重新解析相機素材。
- 初次整份清單確認未保存時，以明確警告保留原手動下載能力。若無法確定是否已保存則停止。Clear 移除紀錄並退休舊 epoch，不刪目的檔，也不重新建立舊清單；已開始保存的實際完成事實仍由原 finalization 邊界判斷。
- 清單與歷史嘗試共用 100 列限制；計畫、active/unknown 列受保護，不為新計畫逐列截斷或靜默移除舊計畫。整份工作清單因容量被拒時，現有 caller 顯示通用「清單無法保存」警告並保留手動保存；尚未顯示容量專屬原因。
- 舊 schema 1/2 嚴格讀取，成功原子寫入才遷移到 schema 3。持久內容只有本地 ID、清理後名稱、時間、順序、目的地類別與結果，不存 URL、token、相機 session/wire ID、SAF URI 或目的地權限。
- 歷史 UI 顯示整份清單、列順序、queued 與既有結果；英繁中一致。原單筆／動態前景匯入的 best-effort 歷史介面保留。

## 驗證界線

本機 Kotlin 編譯、JUnit、Android instrumented/runtime、正常遠端 CI 全部 **NOT_RUN**。目前只有受控測試來源、來源 review、XML/差異靜態檢查；不得把測試方法數或預期紅綠稱為執行結果。

雲端工作區重置後，這批從 verified accepted tree 建立獨立來源，沒有沿用遺失工作區的測試收據。原 USB storage 候選另列待重建／重驗，沒有混入此批。

正常必要 CI 不移除；本批尚未推送，Android runner 的未批准 KVM 權限動作也未執行。完整 App／provider／實體相機驗收依使用者指示在開發後集中進行。下載完成只代表當次輸出 API 的完成邊界，不保證硬體 fsync、檔案永久存在、hash 或可解碼。

## 最終來源 review

兩個未撰寫 production diff 的來源 review 分別核 caller/UI 與 storage。已修 live truth 缺口：持久化狀態不可寫時，原同 owner/epoch 已開始列的實際終態仍更新記憶體，但保留不可寫及警告、不覆寫未知磁碟；Clear 後舊 receipt 不得復活。UNCONFIRMED 文案改泛用結果未確認，不錯誤歸因一定發生過 App 重啟。

49 個 store 測試方法與 17 個新 caller 測試方法是待執行來源。新增極小 atomic-move seam，production 原 Files.move(ATOMIC_MOVE, REPLACE_EXISTING) 不變；受控來源涵蓋 throw 前未安裝、move 後 throw 但回報 UNKNOWN、非空與空 baseline Clear、晚完成的真實記憶體結果。最終獨立來源 review 無剩餘 blocking finding；沒有宣稱 runtime 紅綠。

## 既有 instrumented fixture 整合

五個既有 Gallery／SAF／JPEG 接收／recording journey fixture 改為從 planned rows 讀取批次 receipt，獨立單檔與前景匯入仍明確走 legacy entries。共用測試 helper 拒絕重複 receipt，queued 列不冒充已開始。原 bytes／GET／cleanup／cancel／old-owner／播放斷言保留；Gallery 的終態寫入失敗改在新流程第三次 write，先確認已發布 bytes 與 IS_PENDING=0。單檔 SAF 原第二次 write 不變。

新增完整 50 列冷讀 UI 測試來源驗證 5 completed／1 unconfirmed／44 queued、穩定列順序、最後一列可達、clear／reload 和零相機命令。清除與重讀均要求無 warning 且 writable，並直接讀 durable snapshot，避免把 READ_FAILED／CLEAR_FAILED 的空狀態當成功。獨立來源 review finding 已收斂；instrumented 編譯與執行仍 **NOT_RUN**。

## Android 完整交付群組準備

2026-10-11 13:29 UTC：worklist已保存本地commit `16e76e27f2cf931dd918727c962bbfdb6d5da21b`；本次與USB storage重建commit `525d99f5801f566f3ec7fdc08b653aa034766b5a` 合成23檔獨立候選，路徑無重疊且同基底，不混PC／iOS或diagnostic workflow。上述原「另列待重建」是早期狀態，USB已完成來源重建，但不能繼承原遺失候選驗證。工具恢復不等於編譯成功：原AGP解析Network unreachable，Kotlin/JUnit/Android仍NOT_RUN。正常CI的API34／36含sudo udev KVM權限變更，未取得本批批准前不觸發；新draft亦待當次明確答覆。
