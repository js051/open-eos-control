# libgphoto2 Bulb 停止責任修正（2026-10-17）

## 範圍與原本缺口

對應原功能表 G04／#65（Bulb），基準 `e19dab024d0ea980c426d893cb30c36c1d77c08a`。

既有 `GPhoto2Session.start_bulb_exposure()` 在 Press Full 已套用但 CLI 回應遺失、隨後 Release Full 也失敗時，只把 cleanup 失敗附在例外備註。因 `_bulb_exposure_active` 仍是 false，狀態錯報已停止，明確 Stop 只讀 status，沒有再次送出 release。已成功 Start 的 Stop／close 也重新探索 release 能力；能力遺失或 descriptor 改變會遺失原停止路徑。

使用 production `GPhoto2Engine` 搭配既有合成 `FakeRunner` 重現：合成相機仍在 Press Full，App 可讀狀態卻為 `bulbExposureActive=false`、`shutterReleaseUnconfirmed=false`；明確 Stop 新增 release 命令數為 0。這是控制程式／CLI 回應層的重現，不是實體相機曝光證據。

## 修正

- 在 press 派送前，保存已驗證的 immutable release path/value；不保存可變 descriptor，也不把後續能力探索用於停止。
- 未取得 release 回應時，保留原 owner 的責任，回報 `SHUTTER_RELEASE_UNCONFIRMED` 與 null／true 狀態；明確 Stop 只重送原 release。
- 停止回應完成後立即清除責任；後續 status 讀取失敗不重送 release，也不抹掉已收到的停止回應。
- 未知停止時，阻擋新拍攝、Bulb Start、AF／半按、Near/Far、倍率、Live View Start、REC Start、設定與時鐘寫入；保留讀取、REC Stop、Live View Stop、Bulb Stop。事件相機 I/O 持續暫停。
- close 最多嘗試一次原 release；失敗時本地 session 仍關閉，但回報 502 警告；同次 stream／viewfinder cleanup 例外不得遮蓋此警告。新 session 不繼承舊 release，不聲稱舊相機實際已停止。
- 只有已收到 Start 回應且後來成功收到 release 回應的旅程，才加入 Bulb observed capability；未知 Start 的 cleanup 不補造成功曝光證據。

## 驗證

`bridge/tests/test_gphoto_bulb_recovery.py`：27 個合成案例。

- 最終新增案例基準：24 failed／3 passed。
- 修正：27 passed。
- 核心修正的相鄰驗證：當時新增 24 案，加 `test_gphoto2.py`、`test_recording_lifecycle.py`、`test_maintenance_lifecycle.py`、`test_session_shutdown.py`、`test_api.py`，共 408 passed。
- 後續補上 close 清理錯誤不得遮蓋 shutter-release failure，另驗證明確 start-stop-start：最終 27 個新增案例，加 shutdown 11 案、既有 gphoto Bulb／close 2 案，共 40 passed。未重複宣稱最後 close 修改已重跑完整 408 案。
- 包含原 API route 的失敗 status／明確 retry，以及 DELETE 失敗後 replacement session 隔離；沒有連接網路相機或 native libgphoto2。
- 既有 Starlette/httpx deprecation warning 1 項；未安裝或升級依賴。

可重跑指令：`PYTHONPATH=bridge python -m pytest bridge/tests/test_gphoto_bulb_recovery.py bridge/tests/test_gphoto2.py bridge/tests/test_recording_lifecycle.py bridge/tests/test_maintenance_lifecycle.py bridge/tests/test_session_shutdown.py bridge/tests/test_api.py --tb=short -o addopts=`。需使用已有 pytest 的驗證環境。

## 邊界

狀態為 implemented；未執行此提交的 CI、原生 libgphoto2／實體 USB 相機驗收，未合併 main、未發布。沒有新增相機命令或推定機型相容性。

本次僅補 libgphoto2 Bulb 的既有停止責任。PC 尚未完成的 pending Start deadline／HTTP 到達順序問題，以及獨立 AF／半按的釋放責任，不以此測試宣稱已解決；手機呼叫 Bridge 可收到同一既有狀態欄位與錯誤碼，但未進行 native 手機操作驗收。

### 後續共同來源重驗

整合產品384d7e7b已用最終27案＋上述全套相鄰測試重驗411 passed，另加static UI15案，總426 passed／既有warning1項。PC完整module3738 passed，真Firefox只覆蓋PC caller回應恢復，不冒充gphoto CLI實機；詳見 [共同來源紀錄](pc-bulb-response-recovery-2026-10-17.zh-TW.md)。
