# iOS 完整批次工作清單開發批次

## 原計畫缺口與本批範圍

P2 的「取消／程序強殺後可恢復工作清單」不能只靠已開始的下載嘗試紀錄。先前 `beginBatch` 沒有保存選取清單，每次 `beginItem` 才加入一筆；選取 50 項並在第 6 項中斷時，剩餘 44 項不會出現在重新開啟的紀錄中。這是來源確認的缺口，沒有把閱讀程式或重新建立 store instance 當成真實 OS kill 驗收。

本批只補完整、離線且惰性的選取清單保存與顯示，不加入自動下載、重連、續傳、Range 請求或相機／目的地重新綁定。每次新準備仍由使用者選取目前連線中的素材。原單檔手動流程與前景匯入的動態嘗試紀錄保留。

## 保存契約

- manifest v2 的 planned batch 保存隨機本地 batch／row UUID、建立時間、原始選取順序及清理後的顯示名稱。沒有原始 media ID、相機 serial、URL、token、session、目的地路徑、bookmark 或 handle；新計畫名稱移除 URL authority／query／fragment，再沿既有 basename sanitizer。
- 整份清單原子 admission，不能只留前 100 項。已記錄嘗試加未開始的預留 row 合计最多 100 項；manifest 上限 256 KiB、保留副本 512 MiB 不變。不淘汰舊計畫、不截短、不替使用者清紀錄。
- 每項原檔 GET 前，對應的預留 row 原子轉為 started receipt。已知 plan 的 row admission 失敗即在 GET 前停止，該項仍是未開始，不能一邊留下 queued 一邊偷偷下載。
- queued 只表示這份已驗證清單中的 row 尚無被 admission 的嘗試。cold load 將 started 投影為 unknown，不猜測已有多少 bytes，也不把讀取投影寫回磁碟。prepared 保留原先準備成功的事實，App 副本目前 missing／changed／unsafe 另列。
- 讀取有效 v1 只顯示舊嘗試，不捏造完整清單；下一次成功 mutation 才寫 v2。既有 receipt ID、檔案 identity、tombstone 與 export lease 保留。未知 schema／額外欄位／不合法連結／重複 ID 等 fail closed。
- clear 在同一 epoch-changing commit 清除完整清單與嘗試，已分享的副本仍由既有 lease 保護。舊 ticket 不得重建清單。

## 手動能力與失敗界線

為保留既有手動能力，整份 plan 最初 admission 失敗時，批次仍可沿原手動下載流程工作，但明示「完整工作清單未保存」。沒有保存成功就不顯示完整 queued roster，也不以零散或截短紀錄冒充完整恢復。有效暫存原檔仍可明確分享，不能因歷史／保留副本失敗將有效下載改報失敗。

若 plan 已保存而某 row 無法記錄 started，該 row 不發出 GET，批次停止且保留未開始狀態。若使用者在批次進行中明確清除歷史，epoch 已退休的計畫不再可寫；原手動批次可繼續，但明示工作清單不可恢復，不能重建被清除的紀錄。

Stop 在 plan reply 或 item reply 期間到達時，後續必須重新檢查 caller cancellation 與 session owner。Disconnect／新批次不得被舊 reply／defer 污染。保存的顯示名稱不是來源身分，不能拿它自動匹配或重試未知嘗試。

## 離線顯示

依計畫建立時間分組，每組保留選取順序及第幾項／總數，未開始與實際嘗試結果分列；舊／動態嘗試單獨標明「完整選取清單未知」。同一 receipt 不重複列在兩區。分享入口只接受目前可用的 prepared App 副本。開啟、刷新或清除清單不會發出相機請求或目的地 picker。

此 10 月 11 日批次當時未實作明確重試／重新綁定。後續 [10 月 13 日手動接續批次](ios-manual-worklist-continuation-2026-10-13.zh-TW.md) 已增加逐列選取目前來源、確認與新連結嘗試的 UI→store→download 流程，來源仍不能由檔名、size、時間或舊 IP 自動證明等價。新增 Swift 編譯／XCTest／UI 集中驗收仍為 NOT_RUN，不以程式實作宣稱整個 P2 已驗收。

## 驗證狀態

新增 14 個 store 控制與 6 個 App 接線控制來源：完整 50 項 5 prepared／1 unknown／44 queued 重建、整份／單項 atomic failure、容量／manifest byte bound、v1 migration、hostile keys／identity links、動態與預留容量共存、未知 size／相同顯示名、clear／late callback／pinned export，以及 plan reply cancellation／新 session、單項 admission 失敗不得多 GET、初始保存失敗仍可手動準備。

重新建立 store instance 是受控重新載入，不是真 OS process kill。沒有本地 Swift／Xcode，新增 Swift 編譯／XCTest／UI runtime 目前 **NOT_RUN**。完整 App、冷啟動／程序強殺、OS 分享 provider 與真相機驗收依使用者指示在功能開發後集中；原必要正常 CI 不移除或降低。沒有 main 合併、發版或 KVM 權限變更，release HOLD 保留。

## 同批既有 UI failure 收斂候選

PR236 `0f73af12b5f9405b78e857d3f0f2f05d27f73ac4`／CI `38112489906` 已終態 failure。Core 301/301（含真正 chunked overflow fixture）及 App 312/312 通過；UI 29/30。原日期／評分／資料夾雙語大字體旅程在 folder Apply 後尋找 preview001，665行失敗。artifact `11692596923`，196667983 bytes，SHA256 `a1bd048d0760f217253d5558b63e0eb77e073535bb54449e848e5e58a583e9ed` 已下載核對並查看真 PNG。

三次搜尋確實使用 hold0，但沒有解決問題：viewport y180.667...874，每次觸控路程554.667pt，最後按鈕 y940.333...988.333，仍不可點；8秒 budget 因同步 XCTest gesture/idle 到9.842秒才返回。原 search-flick 候選失敗保留，不把 Core／App 全綠當 UI 全綠。

本批新增受限的幾何候選：僅尚未 materialize／invalid frame 的 generic 搜尋，使用已裁掉固定 header/footer 的 viewport，兩端保留 min(22pt,10%height)內距。該失敗幾何的觸控路程649.333pt；已知target的原精準修正（126.333pt）、cameraActions手勢／速度／hold、所有deadline及業務assertions保留。新增純幾何控制核完整clipped坐標、44pt短viewport、invalid與不包含viewport時拒絕。

這只证明觸控端點與路程計算，不證明相同內容位移、慣性、全部畫面可達或失敗根因修復。Swift/XCTest 本地 NOT_RUN；與完整工作清單同一次正常CI驗證，不另開一輪只為此猜測。
