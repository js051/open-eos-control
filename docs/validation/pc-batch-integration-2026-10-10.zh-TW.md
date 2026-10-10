# PC 四批修正整合驗收（2026-10-10）

本整合候選沿用 #227 原分支，不新增 PR。已驗收 main 基底為 `65fbabf00c78924228ec7cbfe22d733ef806094f`，依序套用以下完整 base-to-head 修正，保留原提交作為可稽核來源：

| 批次 | 原始 head | 內容 |
| --- | --- | --- |
| #224 | `84af87b921ac1576cc735da33dd1a58efb853568` | 刪除回應的 session ownership |
| #225 | `27a3a96e4af4a726891b43a18229729fedef3079` | RTP 啟動／狀態回應 ownership |
| #226 | `deeeff718dc8ea8ab312aa2f1c9f263bf184f9ee` | 已載入素材日期範圍、嚴格日期解析、焦點驗收與大字級溢出修正 |
| #227 | `abd71d0b36a2f4dd3dccbcfa3a7e9e3604fe4c79` | 未知大小不誤顯示 0 B |

## 整合處理

四批 production source 三方套用均無衝突。package.json 的同一測試列需保留四個新增 suite，合計 14 個模組腳本。CCAPI browser 的共用插入點需同時保留刪除 ownership 與日期旅程的完整 helper 和呼叫。

整合時實際重現兩個 fixture dependency 缺口：日期 suite 的兩個 preview 案例缺少 production formatMediaSize；大小 suite 的 27 個案例缺少 production renderMediaDateFilter。補入真實 helper 及 mediaDateRange 初值，不以假 helper 取代，也未刪 assertion。原失敗保留為整合前證據。

## 本地驗收

最終 14 個模組腳本全部通過，2.631 秒，觀測 aggregate RSS 122,944 KiB。日期／刪除／大小 focused 共 117 項通過，其中新增三個交互案例並強化六個 stale-delete 控制：

- 已套用日期範圍時，同 session 刪除保持範圍、更新剩餘數量並關閉已刪 preview；只送原一次 DELETE。
- Disconnect/reconnect 後日期狀態清除；舊刪除成功或失敗不得覆寫新狀態。
- 範圍內素材明確讀取 metadata 後，已知大小改未知會移除容量標籤；只有一次使用者發起的 info GET。
- Metadata 日期移到範圍外時，卡片消失，已開啟資訊保留同一素材目標，不誤刪已載入資料。

執行限制：單 CPU、60 秒、640 MiB RSS 停止線；無超限。2026-10-10 恢復時，原整合 patch 與所有六個 source/runner hash 均一致，未把中斷或未知結果當作通過。

這些是 production-function 與合成 DOM 證據。完整正常 Chromium、HTTP、Windows package 及 JVM／debug 等必要 CI 必須對本次整合 head 重新驗證。四個原 PR 的各自綠燈不可代替整合驗收；此文件建立時新整合 CI 尚未執行。

## 邊界

2026-10-10 使用者已明確批准四批完成整合檢查後正常合併 main 並驗證 main；原先 #224 ready 的拒絕歷史保留。新整合 commit 以 #227 為 first parent，保留 #224／#225／#226 原 heads 為其他 parents，使正常 merge commit 可保存四批完整 ancestry。必須先通過整合 CI、核對當前 heads/base，才進行後續合併。此授權不包含發版或 KVM 權限變更。#219 原始 Compose 根因 UNKNOWN、凍結 v0.13 HOLD 與真機驗收限制不因 PC 整合通過而解除。各獨立批次文件保留當時來源／證據，後續整合狀態以本文件及 exact-head PR CI 摘要為準。
