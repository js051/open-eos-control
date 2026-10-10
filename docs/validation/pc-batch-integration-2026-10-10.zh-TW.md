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

## 首次整合 CI 與修正

`b437b93ca14bc730a54acae5c7583acdd50f049a` 的正常 CI `38016282306` 完整終態失敗：secret、Windows、JVM/debug 均通過；desktop 的英／中範圍外拍後 review 等待 160×120 preview 各超過原 12 秒限制。RTP、刪除 ownership、大小 metadata、英／中日期範圍及後续 delivery/cancellation 案例通過。原失敗未以重跑抹除。

Artifact `11656896211`（2,285,856 bytes）已下载核驗，SHA256 `6835d8af3592f5ace03fe77ab88995b33d4b2db7614ce17d238b297ddd76ec47`。該次未保存逐失敗 DOM/pageErrors，故不宣稱已還原原 run 全部因果。

來源查到獨立可重現的既存機制：capture 的 contents event 會在相簿已載入時背景刷新清單，原 refreshMediaWhenCurrent 無條件關閉 preview、清除 src 並改 generation。使用者剛開啟的拍後 review 因此會被關閉，pending fetch/decode 也會丟棄結果。

新 production-function regression 在未修正來源實際得到 17 項中 8 個行為失敗、9 個控制通過，涵蓋 pending GET、pending decode、已解碼及有／無日期範圍。修正僅讓 contents event 明確要求保留 preview；一般手動刷新仍清理，合併進既有背景請求的手動刷新也立即清理，不新增請求。原 session／generation／scope 防護不變。

正常 browser 原英／中兩案例改用有界真 HTTP 回應 gate，分別控制已解碼與 pending preview 遭遇晚到的背景 listing；保留原 12 秒限制與所有篩選、1/1、導航、單次拍攝 assertion。失敗會在 cleanup 前保存 DOM/pageErrors/請求紀錄及畫面；gate 最後釋放並清理，不讓診斷失敗吞掉原失敗。修正後完整正常 CI 仍須重新通過，不能以本地 synthetic 測試代替。

保留 preview 後另以第 18 案重現導航計數仍舊的問題（17 pass／1 fail），補上成功刷新後重算開啟 preview 的導航，但保留目標、URL 與 generation。最終 18 案全過，15 個完整模組腳本也全過（2.332 秒、122,512 KiB）。新 browser gate 限定 recent listing 的 `limit=61`，並等待畫面顯示新的三項總數，避免誤把舊 COMPLETE 或 latest-review 讀取當作背景刷新已完成。
