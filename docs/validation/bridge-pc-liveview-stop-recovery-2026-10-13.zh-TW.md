# Bridge／PC Live View 停止責任

## 原 P1 缺口

AUTO 只能在舊來源清理已確認後降級。原 Bridge 在 JPEG／multipart／RTP 啟動後回應或首幀失敗時，可能吞掉停止失敗，再啟動下一來源；一般 stop 的 finally 也會清掉重試需要的來源。PC 原 source／FPS 切換則在 stop error 後繼續 start。

本批在相機 start dispatch 前保存原 session 的 method／path／payload。停止失敗保留該責任，清理本地 receiver 不等同相機已停止；後续 Start、AUTO 與 size fallback 必須先成功清理。重試只送原停止操作，不重新 discovery、不重送 Start。不合作取消與 close 仍只能盡力清理，斷網及程序終止不保證物理相機已停止。

## 公開相容契約

- CameraStatus 新增 `liveViewStopUnconfirmed`，描述停止未確認，不宣稱仍有畫面。
- `/liveview/stop` 的既有 LiveViewState 新增 optional `liveViewStopUnconfirmed`；目前 CCAPI 回明確 false 代表其保留的責任已清除。不回報的 engine 仍為 null，沒有推定所有 provider 都有同等證据。
- 遺失 Start ACK 或 Stop 失敗後，客戶端需 `active == false` 且明確 `liveViewStopUnconfirmed == false` 才解除責任。只有先收到合法 `active == true` 的已確認 Start，其第一次成功 Stop 可沿用舊版 bare `active == false`。舊版 Bridge 無法證明不確定狀態時，保留重試與更新提示。
- `LIVE_VIEW_STOP_UNCONFIRMED`／409 表示停止未確認；`LIVE_VIEW_STOP_REQUIRED` 表示已有來源須先停止。沒有新增相機端命令或 endpoint。

## PC 操作與歸屬

既有 Live View 按鈕在失敗後成為停止重試；source／FPS／local preview 切換失敗不再啟動下一來源。睡眠及感光元件清潔也先確認停止，成功清潔後仍可恢復原預覽。開始回覆必須是合法物件且 active 為 true，否則保留停止責任。

Start／Stop 回覆以實際 session object 與 operation owner 綁定；同一字串 session ID 的新連線不繼承責任，晚回覆不得清新 busy／狀態。Stop 退休同連線的舊 Start 回覆。frame error 後仍保留原診斷，cleanup 失敗不冒稱停止成功。

後續競態補強將所有已接受的 status 回覆集中採納：`true` 鎖存於該 session 的停止 owner，後續 false／null／缺欄 snapshot 都不能清除。Start 晚到 ACK 不能恢复新警示已撤銷的 legacy 權限；Stop 等待途中必須使用目前 owner 的證據。觀察 revision 只在 Start admission 和已確認 Stop 時前進，不在 Stop admission 前進，因此仍被原 caller 接受的較早命令 status 可以撤銷等待中 Stop 的 legacy 權限；Stop ACK 後的舊觀察不能重新建立責任。明確 `active:true` 加停止警示的 Start 回覆不啟動畫面或 polling。

caller 本身的舊回覆規則保留：production `beginCameraInteraction` 會使較早 refreshSession 的 generation 過期，該 refresh 會被丟棄；另一方面，仍屬同一 session 的 shutter 命令回覆可以在 Stop 等待時被接受並鎖存警示。兩條路徑分別有受控案例，沒有把被丟棄的 refresh 冒稱為採納證據。

## 已執行與待驗

- Bridge 基準：10個 unittest methods、18個失敗 subcases，包含遺失 Start ACK、停止路徑及 AUTO。修正後新增22個方法通過，含 ASGI 真路由與明确 Stop 回覆。另106方法回歸在新增20方法階段通過；數量不得相加或冒稱同一最終來源。
- 15個既有無 fixture 的 Python Live View 測試函式維持原 assertions 執行成功；不是完整 pytest，也不是實際 RTP 解碼驗證。
- PC 基準7個真 production 函式控制全部失敗；完整候選34個受控 Node 案例通過。涵蓋成功切換、失敗 Stop、legacy／malformed replies、舊 session／並行回覆、維護流程。先前 aggregate 因 VM 未載入新增 helper，以及 RTP fixture 將 Stop JSON 錯寫 null 而失敗；已補實際 helper 與合法既有回覆，未刪業務 assertions。
- 上一階段完整29個 Node 模組全部成功：19個 Node runner suites共715 cases，以及10個原assertion scripts；該階段production候選108個Python unittest方法全部通過（27.271秒）。沒有把較早的106方法再相加。
- 本次 PC warning-latch 競態補強的最終來源重新執行完整29個 Node 模組，全數成功：19個 Node runner suites共728 cases，以及10個既有 assertion scripts。Live View 集中套件46 cases（原34＋新增12）；capture-review新增1個真 shutter status 接受路徑。包含 delayed Start／true→false／late ACK、等待中 Stop 的 missing／null 拒絕及 explicit false 正控制、普通 snapshot 不解除責任、舊 session 與晚 status 隔離、明確警示 Start 不 polling。既有 VM extraction 只補實際 helper imports，不減少 assertions。此補強未重跑 Python、browser 或 CI；`node --check`、`git diff --check` 成功。
- 本批 exact-head 正常 CI／browser 尚待執行。以上不是實體 Canon、網路、PyAV decoder、OS 背景或長時間效能證據。完整 App 與真機驗收仍集中進行。

## Release Assessment

現有公開baseline仍為 v0.12.0 Development Preview；本群屬向後相容的停止責任修正，proposed impact為patch，不調版本。未解條件為本批exact-head必要CI、同樹整合與實機範圍；現有release HOLD維持。本文件不授權main合併或發布。

## 首次正常 CI 與測試來源修正

原 head `67e33772a55275758032071bcca023ec131b6a00` 的 CI38407027603，desktop job 在 Ruff 階段失敗：新增測試檔的 import 空行及迴圈 lambda 未固定變數（I001/B023），後續 browser／完整後端測試尚未到達。保留此失敗；修正僅以 `functools.partial` 固定原呼叫參數並整理 import，不改 production 或移除 assertions。22 個集中 unittest 方法重跑全部通過（0.206 秒），不是 pytest 全套；Ruff 本地不可用，修後 exact-head 正常 CI 仍待驗證。
