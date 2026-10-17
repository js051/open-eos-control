# Bridge／Android 原始控制的 partial ACK 拒收

## 缺口與最小變更

基線 `fd29c906071e15e219a680c5d1e3287ef385b06c` 已有有界控制 body，但 HTTP 206 或帶 Content-Range 的 200 仍可能被當成完整成功收據。原 AF／Bulb Stop 先失敗 503 後，這種 partial retry 會清除原停止責任，並允許新 Start。這不是新的控制命令或相機能力。

本批只增加成功收據的最小 gate：拒絕 206，或任何大小寫的 Content-Range header 存在，包括空值。不新增 JSON／Content-Type 要求，也不新增其他成功狀態白名單。

- Bridge `_request_ok` 重用既有 `_CONTROL_BODY_ROUTES`：POST shutterbutton；PUT／POST manual shutterbutton；POST AF；POST／DELETE Live View；POST RTP；DELETE multipart。非 2xx 已先由原 `_request` 處理。
- Android `requestOk` 在原非 2xx 拒絕處理後，對既有 `stillCapture`、`liveViewControl`、`releaseControl` 三旗標加同一 gate。still 原有 strict JSON reader 保留；旗標併用不能降級。其他 recording／shooting-setting／maintenance 規則未改。
- 保留原成功相容性：200 `{}`、204 空 body、200 空 body、200 純文字，以及既有可接受的 202 純文字控制收據。這些是相容性控制，並非宣稱 Canon 文件保證每一形式。
- 兩平台的 owner／mutex／join／NonCancellable／原 operation closure／警告與 cached receipt 均未改。原 Stop 不確定時仍阻擋新操作；後續完整 Stop 才清除責任。
- 不修改 media GET／range、body deadline、redirect 或其他傳輸政策。

## 已執行的分層證據

所有執行使用既有工具，未下載／安裝、未使用瀏覽器、未發出相機或外部網路請求。Kotlin host 每次 256 MB／1 CPU，工具 SHA-256 核對通過。

### Bridge

新增 `test_ccapi_control_partial_ack.py`，先於產品修改建立 red，並以封存基線與最終相同測試再次重現：

- 基線 30 failed／56 passed，pytest exit 1；修正後 86/86 PASS，exit 0。
- 六個核心 red 使用真 `CcapiSession`、真 `UrllibCcapiTransport._open/request`、真 `BulbRequests`；只有 raw HTTP response／discovery／timer 為 synthetic。原 Stop 503 → 206／200+range／200+空 range 後，synthetic peer 仍 active，原 owner 與 Stop 必須保留，警告不可消失，新 owner 不得發 I/O。
- 再收到完整 204 時，只重試原 method／path／payload；同一 owner 重試走 cached receipt；之後才允許新 owner。
- 另外 24 個 red 覆蓋八條原 physical-control route 的三種 partial 成功收據。
- 56 個基線已通過的控制涵蓋五種原成功形式、正常 media／metadata／unrelated range 路徑、非 2xx 帶 range 的原 HTTP 錯誤分類，以及原 explicit-status／maintenance 規則。
- 新測試加既有 AF／Bulb／Live View／still／shooting-setting／recording／Bulb-owner 回歸合計 466/466 PASS。範圍為收據、狀態與原控制責任，非實體相機驗收。

### Android host

新增 `run_android_control_partial_ack_host.py`：

- 基線 compile exit 0、JUnit exit 1，13 案中指定 9 案 red／4 案原已通過；修正後 compile exit 0、JUnit exit 0，13/13 PASS。runner 驗證指定失敗名稱，缺依賴或編譯失敗不可當 red。
- 六案從真 held AF／Bulb public entry 經真 `commandOk`／`requestOk` 與完整 release owner 執行，覆蓋 Stop 503 後三種 partial retry；驗證仍阻擋新 Start、原版本／method／payload 不被更新 discovery 替換、完整 204 後 retry 不再 I/O。
- 其餘三個 red 是 partial Start 必須進原 cleanup、Live View partial ACK、stillCapture 的合法 JSON partial ACK。Live View／still 案透過 fixture 旗標呼叫真 `requestOk`，不宣稱這個 runner 執行其 public entry。
- 四個原已通過的控制保留成功 body、typed 非 2xx 純文字錯誤、unrelated partial 回應，以及旗標併用的 strict JSON 優先。
- 另跑既有原 entry／wiring 回歸：Direct release 14/14、Live View 12/12、still 3/3 PASS。
- Call／Response／InputStream／discovery／status 為 synthetic。header lookup fixture 大小寫不敏感，空值仍是存在的 header；此層不證明真 OkHttp parsing 或 socket 行為。
- 完整 `CcapiClient.kt` 與新增 native 測試的 Kotlin PSI syntax 檢查通過。這不是完整 Android type-check。

## 已編寫但未執行

`CcapiDirectReleaseTransportTest` 新增四個真 OkHttp／MockWebServer 測試，涵蓋 AF Stop、manual PUT Bulb Stop、partial Start cleanup、五種完整成功形式。每個 partial 組合含 206／200+range／200+空 mixed-case range。

這四個新增 native tests 均為 NOT_RUN：既有核對快取沒有 OkHttp／Okio／MockWebServer；未下載依賴或啟動 Android native build。完整 Android type-check、真實 OkHttp 執行、exact-SHA CI、實體相機驗收也均為 NOT_RUN。沒有以 source guard／host fakeCall 取代這些缺少的證據。

## 重現與收據

[分層收據與日志](evidence/control-partial-ack-2026-10-17/) 以 filename／SHA 分列保存來源／fixture／工具 hashes、red／green 退出碼、回歸與證據邊界；交付日志正規化工作空間路徑與尾端空白，原日志 hash 另存。完整原始本地 evidence 保留，未提交重建編譯產物。

- Bridge：指定相應 checkout 的 bridge 為 PYTHONPATH，以既有環境執行 `python -m pytest bridge/tests/test_ccapi_control_partial_ack.py -o addopts= -q`。基線使用同一新增測試與 `fd29c906` 的 production／support files。
- Android：既有工具目錄作 `--jars`，新 runner 分別 `--phase baseline --ref fd29c906` 與 `--phase current`，使用不同 `--out`。
- 回歸：`run-android-direct-release-transport-host.py`、`run-android-liveview-transport-host.py`、`run-android-still-transport-host.py` 的 `--phase current`。

## Release assessment

影響為 patch：修正原 physical-control 收據不能清除原停止責任的缺口。本批為 implemented，交由整合審查；未單獨推送、未改版號、未合併／發布。不宣稱 PR ready、main accepted、preview released 或實體相機驗收完成。
