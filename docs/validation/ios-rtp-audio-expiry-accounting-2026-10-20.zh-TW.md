# iOS RTP：排程返回時的到期計數與所有權（2026-10-20）

## 問題與證據邊界

Apple dev run [39492150199](https://github.com/js051/open-eos-control/actions/runs/39492150199) 的 App 測試為 853 項、1 項失敗：`RTPAudioLifecycleTests.testScheduledBufferExpiryFlushesBeforeFiveSecondFailureDeadline` 在虛擬時鐘設為 1 後，要求 `expiredOutputBuffers == 1`，實際讀到 0。原始提交為 `c4db55061385e91cbf2eefb9163fd5afd82768ec`。

來源可確認兩個不同的時序問題，但該次短日誌不能證明唯一成因：

- 測試輸出在 `schedule` 返回前便增加 callback count。若此時跨過原來的到期點，production 的 schedule-return 路徑會清空 pending buffers 卻漏加 expired count；timer/snapshot 路徑則先計數。多等一會不能補回已清除的計數。
- Controller 的狀態在鎖內複製、鎖外發送。較新的 publication 可能使一次同步 poll 的 publication 不再被接納，因此立即讀 fixture 的最後 delivery 也可能讀到較舊狀態。

此外，原 schedule-return 程式把 token/incarnation 檢查與到期退休分成兩次鎖定；舊工作通過第一次檢查後，若所有權替換，第二次鎖定的退休不應影響新輪。

此批修正到期計數與最後所有權決策，不宣稱該次失敗已被唯一歸因，也不延長期限。

## 最小產品變更

`IOSCcapiRTPAudio.swift` 在同一個 output lock 區段完成 token/incarnation 驗證、原到期點比較、pending 計數及邏輯退休。計數沿用既有規則：播放器退休會清掉全部 pending buffers，所以在清除前計入同一批；已被 timer/snapshot 退休的 incarnation 不可再次計數。

實際 native `stop` 仍由既有 serial native queue 執行，不在 output lock 中呼叫；停止可能等待 render callback，不能為了原子性製造反向死鎖。邏輯撤銷與實體資源退休仍分開，新輪 native prepare 不能超車舊輪 stop。

範圍限制：相鄰 `mayPrepare` 失敗與 `reserved == false` 的 cleanup 分支，仍會在 token/incarnation 不合時呼叫退休，可能推進共用的邏輯 incarnation。那些分支原本已在同一鎖內判斷與清理，這次保持原文不變。依 production 規定的 serial decode queue 與 native queue，新輪實體 prepare/schedule 不能超車舊工作及其排入的 stop；這不是「舊工作永不接觸新輪任何邏輯狀態」的全面保證。替換測試覆蓋受支援的 held-schedule 順序，不宣稱強迫重現原 split-lock 窄窗口，也不等於所有相鄰 cleanup 路徑已完整證明。

可注入的 expiry timer 只控制測試何時送出 timer callback。預設仍是 `DispatchSource.makeTimerSource(queue: callbackQueue)`、`.now() + max(0, deadline - now())`、先配置 handler 與保存 watch 再 resume；取消、弱引用及 incarnation guard 不變。單調時鐘預設仍為 `ProcessInfo.processInfo.systemUptime`。Controller、Core codec、真實 AAC 解碼器與 AVAudioEngine 輸出沒有變更。

## 可反駁的驗證

1. 凍結原始來源。為了讓新測試能選擇 callback 時機，baseline 只套用與候選相同的 timer 注入接縫，保留原有 schedule-return 演算法；不能把這個接縫誤稱為未改一字的原始二進位。
2. Held schedule 在 clock=0 入場；clock 精確移到 1，manual timer 不發送，釋放 schedule 並等 consume 確認返回。要求 expired count 精確等於 1，並另等 native stop 完成。Baseline 預期只在漏計數斷言失敗，不接受編譯／fixture setup 失敗作為 RED。
3. 覆蓋小於 1、timer-first、snapshot-first 的順序，重複到期不得重複計數，過期回呼不得補算 rendered frames。
4. 舊 schedule 被保留時撤銷／替換 token，之後舊工作與過期 timer 不能退休、計數或回報新輪成功；必須有實際替換後的輸出見證，不只檢查鎖的文字。
5. 原 controller lifecycle 測試先觀察提交已完成，再於固定虛擬時間 1 等待被接納的狀態。保留原 `== 1`、native retirement、可恢復、舊回呼不算呈現及歷史呈現不算當前進度等斷言。全部既有 1 秒 freshness 與 5 秒等待失敗邊界維持不變。

## 執行狀態與限制

2026-10-20 21:31 UTC 離線 gate 已完成：

- Baseline 的 4 個直接管線測試中，只有 native-return 案例失敗，恰為預期的 2 個 `0 != 1` 計數斷言；另外 3 案通過。候選 4 案全部通過。
- 235 項 validation tests、125 項 CI helper tests、原有完整 Ruff 命令、一般與 fixture Swift 語法檢查、來源／預設接縫／雜湊檢查通過。
- 20 個其他原 lifecycle 測試方法逐字未改；被同步化的原方法保留全部原斷言。Controller、Core、AAC decoder、native output、退休方法與 real-picker UI 檔案逐字未改。UI SHA-256：`05a60bbd2bfc3064451fa17f094a2435d72b541cf10a41989740b8609487af37`。
- 最終 gate 5.25 秒、2 CPU，sampled process-tree RSS 峰值 90,872 KiB，結束空間 2,324,512,768 bytes，未跨 2 GiB floor。RSS 是取樣的程序樹加總，包含各程序重複計入的 shared pages，不是完整連續峰值。
- 第一次來源檢查因外部 harness 把 controller 檔名寫錯而停止，已保留失敗證據；只改正 proof script 的實際路徑 `IOSCcapiRTP.swift`。它不是產品 baseline RED，沒有重設原 5 分鐘期限，也沒有修改產品／測試／斷言。
- 使用既有工具，不安裝套件；合成測試沒有開相機、音訊裝置或外部網路。

Linux 只會驗證來源完整性、既有來源／workflow helpers、Swift 語法，以及抽出原 production pipeline 與測試後配合最小 AV 型別替身的合成順序。這不是 Apple SDK typecheck、AVFoundation、真實音訊、喇叭或實機驗收。

原始發布前離線 gate 與來源審查已通過；下節補記其後取得的 Apple runtime 證據。後續任何發布仍須確認精確 dev HEAD、沒有 open head:dev PR、正常 hooks 及實際 workflow fanout。三檔候選在既有規則下只觸發完整 Apple dev validation，沒有 Android/KVM 或 workflow 修改。Apple job／test step 原有 55／45 分鐘限制不變，不做自動重跑。

同次舊 run 的 real document-picker failure 已由匹配 `.ips` 確認為 DOCWeakProxy/XPC 的 SIGABRT；它是獨立未解問題。本批不改 picker、原始 real-picker UI 測試或其識別／安全範圍契約，RTP 綠燈不能當作 picker 已修復。


## Source-pinned Apple runtime 補記

實際發布 source：[`134fd67b8b99bf0d4c3daad482f6fb8c0af8961f`](https://github.com/js051/open-eos-control/commit/134fd67b8b99bf0d4c3daad482f6fb8c0af8961f)，tree `0f08121b2eed88ec3f22c0022904117f923c054c`。既有 Apple dev run [39501409561](https://github.com/js051/open-eos-control/actions/runs/39501409561) 為 push／attempt 1，沒有其他平台 fanout 或重跑。

- 857 項 App tests 全通過，其中 RTP lifecycle 25／25，包括原 `testScheduledBufferExpiryFlushesBeforeFiveSecondFailureDeadline` 與新增 native-return、timer-first、snapshot-first、replacement 四案。這提供原 controller/runtime 與 Apple SDK 下的實際驗證，不再只依賴 Linux 型別替身。
- Picker ownership App tests 為 36／36；UI 為 43／44。雙語 real cancel/reopen 通過，原 real selection 六情境測試在第二個 English lost-ack 情境的 line 1327 失敗；第一個 English cancel/reconnect 已通過，後四個情境未到達。
- App/UI step 為 2026-10-20 21:39:11–22:15:53 UTC，36 分 42 秒，沒有超過原 45 分鐘限制。整個 Apple run 於 22:17:14 以 failure 結束，不能把 RTP milestone 的 native GREEN 寫成全 Apple GREEN。
- 本次 completed job log 記錄 real file tap 後的 unexpected termination check，但沒有同次 crash stack／reason；不把前次確認的 DOCWeakProxy stack 自動套用於本次。Picker 仍是獨立未解問題，本批沒有 retention、UI 識別或結果斷言變更。
- 保留原 1 秒／5 秒邊界、相鄰未修改的 logical-cleanup 限制、原 UI SHA-256，以及不得拼接不同 source SHA 的成功為共同 HEAD 全綠的界線。這不是實體音訊裝置、耳聽、相機或 release 驗收。

此補記隨下一個有實質功能修正的 Bridge 批次交付，不為補文件獨立啟動另一輪 Apple。
