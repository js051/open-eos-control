# iOS Bridge → 相機連線失敗說明：原 P3 範圍

日期：2026-10-11。此紀錄是本機原始碼候選，不代表 Swift 編譯、App/UI 執行、CI 或實機驗收通過。

## Wire 邊界與相容性

- 僅掃描 `GET /v1/cameras` 與建立連線 `POST /v1/session` 可接收新增說明。必須同時符合實際 HTTP 502、`error.code` 精確等於 `CCAPI_DISCOVERY_FAILED`、`error.engine` 精確等於 `ccapi`。
- `error.connectionReason` 必須是下表中精確字串；不 trim、不轉型、不比對 message 或其他診斷文字。缺欄位、null、數字、布林、陣列、物件、新值、錯誤大小寫、其他 status/code/engine 均維持既有分類。保留既有 2,000-byte 錯誤 JSON 上限；無法完整解析的內容不提升為相機證據。
- `DesktopBridgeError.http` 保留原七個關聯值與所有既有 case。只有上述連線邊界新增 `DesktopBridgeCameraConnectionError`，其 `httpError` 保留原 status/method/url/code/message/feature/engine 與既有 localized description，`reason` 為封閉 enum。
- `CameraConnectionFailureReason.classify` 接收新型別並轉為既有分類；App 另保留 `cameraViaBridge` 來源，避免相機端 401/403 原因被誤當 Bridge token。
- `/health`、既有 session 的 info/status/命令、停止、媒體與 DELETE/close 仍使用原 `DesktopBridgeError`。`CAPTURE_STATUS_READBACK_FAILED` 轉譯、`SHUTTER_RELEASE_UNCONFIRMED` 判定、release ownership 與 cleanup 路徑未改。

## 八個原因與呈現

| Wire 值 | Core 分類 | App localization key |
| --- | --- | --- |
| AUTHENTICATION_REJECTED | authenticationRejected | connection_bridge_camera_authentication |
| TLS_ERROR | tlsError | connection_bridge_camera_tls_error |
| HOST_NOT_FOUND | hostNotFound | connection_bridge_camera_host_not_found |
| TIMEOUT | timeout | connection_bridge_camera_timeout |
| UNREACHABLE | unreachable | connection_bridge_camera_unreachable |
| HTTP_ERROR | httpError | connection_bridge_camera_http_error |
| DISCOVERY_FAILED | discoveryFailed | connection_bridge_camera_discovery_failed |
| ENDPOINT_NOT_FOUND | endpointNotFound | connection_bridge_camera_endpoint_missing |

Bridge 模式沒有相機 URL／username／password 輸入，只有 Bridge URL、token 與相機選擇。新增英語、繁中卡片都指向電腦上的 Bridge 相機連線設定與相機 CCAPI；TLS、DNS、逾時、網路原因均指 Bridge → 相機這一段。卡片不提供錯誤的本機欄位編輯焦點；Connect/Scan 仍須使用者明確重試。Bridge 真正 HTTP 401/403 維持原 token 說明與編輯焦點。沒有新增重試、連線、掃描、相機命令、TLS bypass 或權限請求。

## 控制測試原始碼

`ios/OpenEOSCore/Tests/OpenEOSCoreTests/DesktopBridgeConnectionGuidanceTests.swift` 經真實 `DesktopBridgeClient` + 合成 transport 回應覆蓋：

- 全八值經 session 與 scan wire → client → classifier；原 HTTP 錯誤診斷與 request 路徑保留。
- absent／unknown／malformed reason、錯 status/code/engine、偽造診斷文字、超過既有解析上限；成功回應不因 error-shaped 欄位變失敗。
- 明確重試可成功；沒有自動 fallback 或重試。
- health／status／stop 維持原 HTTP 型別，capture readback 與 shutter release 安全 code 不被 reason 蓋掉。
- `CancellationError` 與 `URLError.cancelled` 在 scan/session 不產生說明。

`ios/OpenEOSControl/Tests/BridgeCameraConnectionRecoveryTests.swift` 經真實 Bridge client + App state 覆蓋：

- 全八值於 Connect 與 Scan 的來源、分類、文案、無本機帳密/token 編輯動作、沒有自動重試、保留無關錯誤。
- 真正 Bridge 401/403 的 token 指引；舊版／無效證據維持 generic HTTP 卡片。
- 只有明確重試才讀取新證據；兩次嘗試不沿用舊來源。
- 受控延遲回應，在 task 取消、Bridge URL/token 修改、模式切換或 disconnect 後不發布過期相機說明。
- 新 message、next-step、retry keys 在英語與繁中資源存在。

## 驗證狀態與剩餘限制

- 原始碼人工檢查與 `git diff --check`：完成；不是 Swift 型別檢查或執行證據。
- SwiftPM 編譯／上述 Core XCTest：**NOT_RUN**，本工作環境無 Swift，未安裝工具鏈。
- iOS App 編譯／App XCTest／SwiftUI 或 Simulator UI：**NOT_RUN**，未執行 Xcode/Simulator。
- CI、推送、commit、合併、發版：此子批次未執行。
- 實體 iPhone、相機、USB 或 Bridge 網路驗收：**NOT_RUN**；合成 fixture 不證明硬體相容性。
- 新型別只對新版 Bridge 的有效 optional reason 產生；僅捕捉 `DesktopBridgeError` 的外部連線呼叫者如需使用新說明，須另處理 `DesktopBridgeCameraConnectionError`，可由 `httpError` 取得原錯誤。原 enum case／參數 API 未破壞，舊 Bridge wire 行為保留。

Release assessment：此候選為既有連線失敗說明的向後相容修正，單獨影響屬 patch；版本與是否發版由整批 EOS 驗證結果另行判定。尚缺本候選的編譯與執行驗收，不宣稱 PR ready。
