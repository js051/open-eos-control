# Bridge CCAPI 控制回應 body 的合作式期限

基準：`25a67ff9fcf4519451d83e13f44356971bc1fdac`。範圍為既有 G04／G05／G06 的 Bridge CCAPI 快門、Bulb、AF 與 Live View 控制回應；沒有新增相機操作。

## 已確證的缺口

`UrllibCcapiTransport.request` 原本僅對 configuration resource 使用 `read1` 與合作式期限。普通控制成功回應以一次 `read(max_bytes + 1)` 累積 body；普通非 DELETE 錯誤的診斷 body 也以一次 `read` 累積。urllib 的 socket timeout 不是整段 body 的牆鐘期限，因此持續滴流可在每次 socket 等待都未逾時的情況下延長持鎖時間。

最初的真實 loopback 測量使用 80ms timeout、每20ms一個 byte、共30 bytes，沒有相機或外部網路：

- AF POST：588ms 後仍回成功200。
- manual shutter PUT：586ms 後仍回成功200。
- Live View DELETE：587ms 後仍回成功200。
- AF POST 已知拒絕400：586ms 後才交付狀態。
- 對照 configuration POST：82ms 回 `CCAPI_UNREACHABLE`；configuration400 約2ms 直接保留拒絕。

同一 production `CcapiSession`／urllib 路徑的 held-AF 反例：Start body 滴流時，原 Stop 在250ms仍等待同一 provider lock，camera peer 只收到 Start。等 body 約590ms完成，原 Stop 才能送出。既有 owner／補償 Stop 是正確的安全順序；此次不以 detach producer 來規避它。

## 最小修正

獨立的 control body 分類只涵蓋以下精確 method／suffix：

- POST `/shooting/control/shutterbutton`
- PUT、POST `/shooting/control/shutterbutton/manual`
- POST `/shooting/control/af`
- POST、DELETE `/shooting/liveview`
- POST `/shooting/liveview/rtp`
- DELETE `/shooting/liveview/multipart`

這些控制與原 configuration reader 共用合作式 body helper：每次最多讀4096 bytes，在讀取前後檢查原 request timeout 算出的 deadline，晚到的成功 body 不能成為成功證據。原 max-bytes／宣告長度檢查與 finally close 保留。

普通非 DELETE 已收到的拒絕狀態不因診斷 body 逾時或讀取錯誤變成未知；診斷 body 盡力讀取，無法及時完成時保留 status 與空 body。快速診斷內容仍保留，Live View 原 HTTP400 尺寸降級語義不改。DELETE 的既有錯誤分流不改。

`configuration_resource`、`strict_control`、HTTP framing、認證與 redirect 選擇不變。普通 AF POST 的既有302→GET追隨仍有測試；DELETE／configuration 不追隨也有對照。這不是 single-attempt／no-redirect 改版。

provider Start／Stop、held-AF timer、原 immutable stop 責任與同步 lock 沒有改動。合作式 body 失敗後仍由原 producer 完成，再送原補償 Stop；Stop body 晚到時保留 `SHUTTER_RELEASE_UNCONFIRMED`、原 owner／operation，只有明確重試 Stop 才能清除，不能重按 Start。

## 可重現驗證

新增 `bridge/tests/test_ccapi_control_body_budget.py`。以既有 Bridge Python 環境執行：

```sh
PYTHONPATH=bridge python -m pytest bridge/tests/test_ccapi_control_body_budget.py -o addopts='' -q
```

固定 suite 使用100ms timeout、每15ms一個 byte、共40 bytes，並保留足夠排程容差；不以 mock 代替 loopback urllib 的讀取。精確八條 route 另以 deterministic late-single-chunk 控制確認 deadline 前後檢查與 close。

- 同一最終34案換回基準 production `ccapi.py`：14 FAIL／20 PASS。
- 修正後：34／34 PASS。
- 14個基準失敗包含三條真實慢速成功 body、已知拒絕 body、八條精確 route late chunk、原 Start 持鎖阻礙 Stop，以及晚 Stop 錯誤清除責任。
- 對照涵蓋 configuration、普通 GET、未列 method／suffix、redirect、快速拒絕診斷、讀取錯誤、body上限與宣告長度截短。
- Ruff：修改的 production／test files 通過。

窄回歸 **445個獨立案例全部通過**（45.94s），包含新增34案；範圍為 `test_ccapi.py`、本次新檔、`test_capture_configuration.py`、`test_ccapi_bulb_recovery.py`、`test_ccapi_live_view_recovery.py`、`test_focus_contract.py`、`test_maintenance_lifecycle.py`、`test_bulb_request_ownership.py`、`test_bulb_cross_control_ownership.py`。既有 FastAPI／httpx deprecation 與重複 OpenAPI operation ID warning 保留，未改測試或放寬檢查。

新增34案另外重跑三次均通過；不加計為更多獨立案例。詳見 [機器可讀收據](evidence/bridge-control-body-budget-2026-10-17/receipt.json)。

## 明確限制與狀態

這是 body 的合作式期限，不是整條 request、Stop 或 Disconnect 的硬性總期限。DNS、open（含既有 redirect 處理）、response headers、任一仍阻塞的單次 read（包括 HTTP chunk framing）與其他前置／後置讀取，都沒有在此證明可被即時中斷。缺少 `read1` 的相容 body 仍走既有 `read` fallback，同樣沒有額外強制中斷保證。

普通 GET（含 JPEG frame、focus telemetry、RTP SDP）、multipart stream／錯誤 body、其他控制如 recording／drivefocus，均未被本次分類擴張。既有 configuration 讀取保持原範圍。這些未改路徑仍可能影響整個 lifecycle；不能將本修正宣稱為 G04／G05／G06 全部有界或完整完成。

測試只使用合成 loopback camera peer 與較短 fixture timeout；正式15秒配置、真相機／弱網／OS／三端原生尚未實測。實作與本地自動化驗證不等於 PR ready、main accepted 或 preview released；本批不推送、不合併、不發版。
