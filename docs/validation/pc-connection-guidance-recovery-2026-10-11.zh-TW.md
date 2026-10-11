# PC／Bridge 首次連線與錯誤復原：原始碼與受控測試紀錄

## 範圍與基準

- 基準：`7a147ee4d14dd129a9b1b27500e2b4aed6614974`。
- 同一批涵蓋 PC 首次連線說明、USB 掃描狀態／取消／重試、CCAPI 位址驗證，以及有證據的錯誤復原。
- 相機控制能力與既有端點不擴充；不安裝 USB 引擎、不略過 TLS 驗證、不改動憑證儲存、權限或必要驗收。
- 相機真機、已安裝 App、瀏覽器完整旅程與本批 exact-SHA CI 仍須各自驗收。本文件不代表 App 驗收通過。

## 使用者行為

英文與繁體中文頁面分別說明電腦 Bridge、USB 資料線／相機模式／其他程式佔用，以及相機 CCAPI 啟用與網路需求。Camera Connect 配對不能直接證明 CCAPI 已啟用；相機帳密與 Bridge token 分開說明。

- 位址必須是明確的 HTTP(S) origin；含 `/ccapi`、帳密、參數、片段或需瀏覽器修正的寫法時，顯示行內原因並停用連線。
- USB 健康狀態同時辨識既有 libgphoto2 與 EDSDK 引擎。沒有引擎時說明應檢查的設定。
- 初始、掃描中、空結果、失敗、已取消及已找到相機使用不同狀態。重試與重新檢查 Bridge 由使用者按下；切回 USB 不會自動重試。
- 保留啟動時既有的一次唯讀自動掃描。掃描使用自己的 request owner 與 AbortSignal；即使舊回應在取消或切換模式後才送達，也不能覆蓋新結果。
- 修正按鈕只聚焦對應的 token、位址或相機帳密欄位，或重新讀取 Bridge health；不自動重試相機連線或發送拍攝／設定指令。
- 重複按下連線或 Enter 不會建立平行 session。相機快門釋放未確認的持續警示保留優先權；改位址、切模式、取消、掃描、重試或語言切換都不能清除它。

## 向後相容的錯誤補充欄位

僅終止於 `HTTP 502`、`error.code = CCAPI_DISCOVERY_FAILED`、`error.engine = ccapi` 時，可增加 `error.connectionReason`。既有 status、code、message、engine 與成功 fallback 行為維持。

```json
{
  "error": {
    "code": "CCAPI_DISCOVERY_FAILED",
    "message": "Failed to discover a Canon CCAPI camera at http://camera.invalid.\n...",
    "engine": "ccapi",
    "connectionReason": "AUTHENTICATION_REJECTED"
  }
}
```

白名單依復原優先順序為：`AUTHENTICATION_REJECTED`、`TLS_ERROR`、`HOST_NOT_FOUND`、`TIMEOUT`、`UNREACHABLE`、`HTTP_ERROR`、`DISCOVERY_FAILED`、`ENDPOINT_NOT_FOUND`。後續 fallback 的 404 不會蓋掉先前實際的 401／403、TLS 或傳輸錯誤；任何成功 fallback 仍直接成功。每次探索重新收集證據。

原因只來自相機實際 HTTP status 或具型別的傳輸例外，不解析回應文字中的 `401`、`TLS`、`timeout`，也不信任相機 JSON 自稱的 reason。舊 Bridge 未提供欄位、欄位格式錯誤或值不在白名單內時，前端顯示原因未明的探索失敗。

Bridge 自己的 token 失敗仍是原本的 HTTP 401 與 Bearer challenge，沒有 camera reason 欄位。前端以實際 Bridge HTTP status 優先判斷；503 內的 `UNAUTHORIZED` 或「401」文字不會被當成 token 或相機驗證證據。瀏覽器網路失敗不宣稱已確認 TLS／DNS 等詳細原因。

## 驗證來源與目前狀態

- `bridge/tests/connection-guidance.test.js`：執行正式 helper、API 與連線／掃描流程；受控 promise 測試取消後晚到成功／失敗、重試、重複點擊、錯誤欄位、實際 HTTP status、行內位址驗證、引擎可用性、兩種語言與持續快門警示。
- `bridge/tests/test_connection_guidance.py`：本機 HTTP 相機 fixture 經正式 urllib transport 與 Bridge API，驗證相機 401 後 404、全部缺端點、成功 identity fallback、無殘留原因、空 API 清單、偽造錯誤內容、transport 型別、原 Bridge 401 合約及無效位址。
- `bridge/tests/ccapi.browser.test.js`：擴充既有旅程，在兩種語言中連續涵蓋 setup／掃描／取消／晚到回應／各類復原／成功連線／中斷連線。保留原有旅程與斷言；新增旅程沿用 12 秒 timeout。瀏覽器刻意送達取消後的舊掃描回應，並等到 JSON 已被讀取，避免提前斷言造成假通過。
- 對照既有 Android `ConnectionFailure.kt` 與 `ConnectionFailureProtocolTest.kt` 的「實際 HTTP 證據優先、fallback 404 不覆蓋具體原因、成功 fallback 優先、取消不變成一般錯誤」原則。

### 實際執行結果（2026-10-11 UTC）

- 06:29:27 至 06:29:38：4 個變更 JavaScript 檔案的 `node --check` 通過；`test:modules` 的全部 26 個 script 依序執行、exit 0。包含本批 13 個新測試，以及既有 session ownership、Bulb、媒體、RTP 音訊等回歸測試。各 script 的 `node:test` 合計 462 個通過；其他既有 script 使用自身 assertion／通過訊息，不能只用 462 表示全部 assertion 數量。
- 此次 Node 執行耗時 10.82 秒，限制一個 CPU、96 MiB Node heap 與 120 秒總期限。RSS 監測 helper 因本執行器沒有 `/proc/<pid>/task/<pid>/children`，錯誤回報 aggregate RSS 為 0；因此不能聲稱已驗證或有效執行 512 MiB 整體記憶體上限。這不改寫各 script 的實際 exit 0。
- 保留前次紀錄：首次以 512 MiB 虛擬位址上限啟動 V8 時，程序在測試開始前配置失敗，該次為 NOT_RUN。之後的首輪新套件有 9 個通過、1 個測試 selector 失敗（將 state 的 `connectionMode` 誤算為文案 key）；修正 selector 後，最終套件 13 個全部通過。該首輪單一 Node 程序觀測到 RSS 39,616 KiB，不能替代整批 aggregate 記憶體證據。
- Python 修改檔案 AST 語法檢查與 `git diff --check` 通過。Python pytest／ruff：NOT_RUN，尚無可用的完整測試環境；不能把語法檢查當作後端執行測試。
- Playwright 瀏覽器旅程、完整 App 驗收、真機與新 exact-head CI：NOT_RUN。瀏覽器測試僅完成原始碼與 JavaScript 語法檢查。

受控 Node suite 完整輸出之 SHA-256：`0263a6617ab4a15f45f61196dbb305715dbab60b582c9e0a4822bceb16807fb2`。任務用監測 helper 已在 repo 外改為依 `/proc/*/status` 的 PPid 建立程序樹，且缺少有效遙測時直接停止；此 helper 尚未重新執行或自測，需在下一次已授權執行前先驗證。

## Release Assessment

- 原始碼版本為 `0.13.0`；最近發布基線須由整批交付者另行核實。
- 建議影響：`patch`，改善既有首次連線與錯誤復原，增加可選錯誤證據欄位，不新增相機能力或破壞舊合約。
- 本文件不授權合併或發布。正式交付仍須本批 exact-head 必要 CI 與整合 App 驗收，並如實列出尚未執行的瀏覽器／真機驗證。
