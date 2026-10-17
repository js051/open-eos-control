# G05 原始對焦／點選白平衡 ACK 完整性

狀態：implemented；僅本地驗證與本地 commit。未推送、合併、發版或接觸實體相機。
基準：`27e6e3cbba2faaaebe48923124608f47dee5410d`。

## 修正範圍

Bridge 與 Android Direct CCAPI 的三個原始命令：

- POST `shooting/control/drivefocus`
- PUT `shooting/liveview/afframeposition`
- POST `shooting/liveview/clickwb`

成功 HTTP 回應若為 206，或帶有 Content-Range（包含空值／大小寫變體），
不得回報成功或新增 observed feature。Bridge 回傳 502 / INVALID_CCAPI_RESPONSE；
Android 拋出完整收據驗證錯誤。既有非 2xx 分類先執行。

這是獨立的三組 method/path 收據條件。沒有擴充既有八條 body-deadline 路由，
也沒有新增 still/live/release 旗標；原 timeout、讀取器、JSON、重試／redirect、
完整 ACK 的舊 body/status 規則、座標幾何／snapshot owner 與媒體 Range 行為保持不變。
PC 程式不變，由原 API 錯誤流程阻止「已接受／成功」提示。

## Red → green 與回歸

- Bridge 新增 38 cases：基準 9 failed / 29 passed → 修正後 38 passed。
- 三個 production API × 七種 wire 回應：9 個不完整 ACK、9 個完整正例、3 個 503。
  使用真正 FastAPI、SessionManager、CcapiSession、Urllib request/open/header normalization；
  discovery/status/Live View frame 與 wire provider 為 synthetic，沒有相機網路。
- 同一批 API 回應交給原 app.js 精確抽出的 driveFocus/tapFocus：基準 9 個錯誤成功提示；
  green 全部改為錯誤且沒有 accepted/success reticle。9 正例仍成功，3 個 503 保留錯誤。
  每例只有一次 mutation；後續 status/capabilities 不會重播。
- Bridge focus、generic ACK、body-budget、幾何與 CCAPI 回歸：353 passed。
- PC 對焦與 owner Node 回歸：113 passed。
- Android exact public-entry host：基準 13 tests / 9 failures → 13 passed。
  包含 driveFocus、tapFocus、clickWhiteBalance、commandOk、requestOk 的原始方法；
  no replay、完整舊 ACK、503 typed error、wrong-method/near-path/media scope、原座標與
  stale snapshot 不送出、原 strict-client selection 均有 assertions。
- Android 既有 generic partial ACK 13、direct AF/Bulb release 14、Live View transport 12、
  still transport 3 tests 全數通過。每個 JVM 上限 256 MB / 1 CPU，工具 hash 驗證通過。
- Ruff 與 git diff --check 通過。獨立 production diff review 無阻擋問題。

## 驗證界線與未執行項目

PC 是 production function extraction；DOM、API adapter、timer/render 為 fixture，
不是瀏覽器端到端。Android host 的 Call/Response/InputStream、discovery、geometry、
status 為 synthetic，並非完整 Android／真 OkHttp socket／裝置驗證。

`CcapiFocusAckReceiptTest.kt` 已撰寫 21 個 parameterized 真 MockWebServer 路徑案例，
本次 **NOT_RUN**。Android Gradle/full native suite、Android 裝置與三端實機均 **NOT_RUN**。
沒有把 host/fixture 證據當成 native、相機套用成功、或相容性證據。

## 重跑與證據

- `python -m pytest bridge/tests/test_ccapi_focus_ack_receipts.py`（從 bridge 或設定 PYTHONPATH）。
- `python scripts/validation/run_focus_ack_api.py --phase current > api.jsonl`
- `node scripts/validation/run_focus_ack_pc_feedback.mjs api.jsonl current`
- `python scripts/validation/run_android_focus_ack_host.py --jars <既有已驗證工具目錄> --phase current --out <本地輸出目錄>`

API runner 的 --phase baseline 只切換預期 assertions；重現 red 必須在原基準 production source 下執行。
Android baseline 模式從 Git 取出基準 production source，使用相同 fixture 觀察九個預期失敗；
current 另外驗證三個 public entry 與 commandOk 不變，且 requestOk 只有獨立收據條件差異。
所有 source、extract、raw log 的 hash 及精簡 API→PC red/green 配對見同名 JSON。
腳本不下載或安裝工具。

## Release Assessment

此修正屬 backward-compatible patch：防止原始控制收據不完整卻顯示成功。
本次沒有查詢遠端 release baseline、調整版本或進行發布準備。未執行的 native／實機
驗證維持明確待驗證，不作成功保證；PR-ready／main-accepted／preview-released 均未成立。
