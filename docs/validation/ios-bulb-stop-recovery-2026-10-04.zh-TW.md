# iOS Bulb 停止責任與恢復流程

## 基底與因果證據

基底為 PR #199 head 180dbfab9ee30a78796bb32f4072d724c0a693e9。先單獨提交只使用既有 public API 的兩個 baseline tests，沒有把待測實作混入。

[Baseline CI 37202721755](https://github.com/js051/open-eos-control/actions/runs/37202721755) 的 head 32c4cbb3ce854b6164fd8d9ec96d3b106c4d1d98：
- Core 成功編譯，173 個測試中只有新增 2 例失敗，8 個斷言失敗、0 unexpected；其他 171 例通過。
- peer 已收到 full_press，再讓其回覆與第一個補償 release 失敗。
- 使用者再呼叫 Stop 或 close，實際命令仍只有 full_press、release，少了要求的第三個 release。
- PUT /ccapi/ver110/shooting/control/shutterbutton/manual 與 POST /ccapi/ver130/shooting/control/shutterbutton/manual 皆如此；原 method/path 與 af=false 斷言保留。
- 這是注入 transport 的 production Core 契約證據，不是實際 URLSession wire 或實體相機。

## direct CCAPI

在開始前保存 session 宣告的精確 release。start／stop／close 使用連線擁有的 operation 責任，處理取消、晚 ACK、重複操作與 actor reentrancy；一般寫入不能穿過尚未確認的 release。

未知不等於 active，也不能假裝 idle。停止失敗保留責任，retry 只送原 release，不重新 start、不重新推測 endpoint。close 等待已擁有的開始 settle，再嘗試原 release；退休連線保留風險，不向新 session 偷送舊命令。

新增 CameraShutterReleaseState 區分目前可重試責任、release unconfirmed 與 nullable active。既有 close() 保留，相容 wrapper 搭配可回傳清理狀態的新介面。成功 release 與後續 status 刷新分開；未 ACK 的開始經補償，不算完成曝光。

## Bridge adapter

保留同 session 的 start/stop 責任、generation 與 revision。只接受 literal Boolean 證據，不把 NSNumber、字串、缺欄位或普通舊狀態當成未知 release 已解決。

正常 legacy 已確認 start/stop 保持可用；模糊 legacy 回覆 fail closed。Stop 不會 replay start。失敗後僅在對應 Stop 已完成、session 未換、未取消／closing 時讀取新 status 來驗證 false/false；一般舊回應不能清除新責任。

DELETE /v1/session/{id} 由連線清理擁有，等待結果後才解除 session 與 close waiters。已取消的呼叫端不讓最後 DELETE 在發送前失效；不擴充權限或跨 session 重試。

## App 使用流程

- 已知 active 或未知 release 都有模式／capability 無關的 Stop／Retry Stop。
- 顯示持續當前警告與獨立上一連線警告，不因普通錯誤清除或 reconnect 混掉。
- operation token／session generation 防止舊 start、reply 或 defer 清掉新操作狀態。
- 恢復未知 start 不閃拍攝成功；沒有隱式重新曝光。
- 背景曝光維持既有政策，沒有新增自動 stop；阻止錯誤 Live View resume，明確 Stop／disconnect 做已擁有的清理。
- 英文／繁中、不同字體、直橫向與新舊警告並存納入測試；實際畫面須 CI 執行後檢查。

## 測試分層與尚未通過的 gate

保留 baseline 2 例不改。新增 Core ownership 16、Bridge recovery 29、URLSession/Darwin TCP 2 個方法（8 組 fresh／pooled 故障矩陣）、App 17、UI 2 個方法。wire tests 要直接量到 full_press 次數，不以 mock 預期代替真傳輸。

DEBUG UI fixture 僅 DEBUG build 且 OEC_SHUTTER_RECOVERY_FIXTURE 為明確白名單情境才啟用；普通啟動／Release 使用原 client。fixture 標記 simulated-shutter-recovery，新增回歸要求實體驗證匯出被拒。它是注入 transport 的 UI 證據，與 Darwin socket tests、實體相機三者嚴格分開。

本機沒有 Swift／Xcode。implementation 的編譯、上述新增測試、wire、UI screenshots 皆尚未執行；已完成 diff check、語系 key parity、原規則機密掃描與 source manifest／patch 完整性檢查。baseline 的 171 綠不能當成修改後全綠。修正必須由精確 head 正常 macOS CI 閉合，不能跳過失敗或放寬 exact-once。

## 相容性與發布評估

v0.10.0 Development Preview 之上的既有流程 patch；不改版本、不合併、不發布。Android Bridge PR #200 為獨立分支，其結果不冒充本分支驗證。沒有實體 EOS／iPhone、其他機型或光學對焦新證據；永久斷線／process death 仍不能保證機身實際停止，使用者須檢查機身。
