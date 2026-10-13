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

## 已執行與待驗

- Bridge 基準：10個 unittest methods、18個失敗 subcases，包含遺失 Start ACK、停止路徑及 AUTO。修正後新增22個方法通過，含 ASGI 真路由與明确 Stop 回覆。另106方法回歸在新增20方法階段通過；數量不得相加或冒稱同一最終來源。
- 15個既有無 fixture 的 Python Live View 測試函式維持原 assertions 執行成功；不是完整 pytest，也不是實際 RTP 解碼驗證。
- PC 基準7個真 production 函式控制全部失敗；完整候選34個受控 Node 案例通過。涵蓋成功切換、失敗 Stop、legacy／malformed replies、舊 session／並行回覆、維護流程。先前 aggregate 因 VM 未載入新增 helper，以及 RTP fixture 將 Stop JSON 錯寫 null 而失敗；已補實際 helper 與合法既有回覆，未刪業務 assertions。
- 最終完整29個 Node 模組全部成功：19個 Node runner suites共715 cases，以及10個原assertion scripts；同一production候選108個Python unittest方法全部通過（27.271秒）。沒有把較早的106方法再相加。
- 本批 exact-head 正常 CI／browser 尚待執行。以上不是實體 Canon、網路、PyAV decoder、OS 背景或長時間效能證據。完整 App 與真機驗收仍集中進行。

## Release Assessment

現有公開baseline仍為 v0.12.0 Development Preview；本群屬向後相容的停止責任修正，proposed impact為patch，不調版本。未解條件為本批exact-head必要CI、同樹整合與實機範圍；現有release HOLD維持。本文件不授權main合併或發布。
