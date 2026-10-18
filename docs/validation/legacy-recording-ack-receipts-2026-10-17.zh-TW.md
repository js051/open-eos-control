# Bridge 相容錄影 API 的原始 ACK 完整性

狀態：implemented；本地修正與驗證，未推送、合併或發版。
基準：`abd5f2e1`。本項不主張現行 PC／Android／iOS 錄影 UI 存在同一缺陷。

## 範圍與結果

既有、需驗證身分的 `POST /v1/session/{id}/recording/start` 與 `/stop`，
仍依相機 discovery 選用 `POST`，或退回已廣告的 `PUT /shooting/control/recbutton`。
新增獨立 ACK-only method/path 清單：206 或任何 Content-Range（包含空值、大小寫變體）
不能成為成功收據。相容 API 使用既有 502 / `RECORDING_COMMAND_UNCONFIRMED`，
在更新錄影 cache 或 observed feature 前返回；不會自動重播 Start／Stop。

非 2xx 仍保留 `CCAPI_HTTP_ERROR`；完整 200 JSON、200 空 body、204 空 body、
200 純文字與 202 純文字成功形式不變。原本已完整 ACK、但稍後 status 讀取失敗的
`RECORDING_STATUS_READBACK_FAILED`、傳輸失敗的 unknown、溫度 preflight 規則不變。

`/status` 仍只提供原有的最後 ACK cache，不是實體相機的新鮮錄影觀察。
不完整 ACK 可能已在相機生效，也可能尚未生效；保留的 cache 不能確認實體停止。
錯誤沿用明確要求使用者另行 Stop 的提示；`/recording/observation` 仍回傳
`recording: null`／`observationEvidence: unavailable`。

沒有變更八條 transport body-deadline 路由、timeout、redirect／重試規則，
沒有變更 modern recording outcome schema、手機或 PC UI，也沒有移除相容端點。

## Red → green

- 70 個新測試：基準 36 failed／34 passed；修正後 70 passed。
- 64 組實際 production API → SessionManager → CcapiSession → Urllib 證據：
  POST／PUT × Start／Stop，含 36 個部分 ACK、20 個完整成功、8 個 503 拒絕。
  每組先驗證 401 不會送出命令；已準備的上一個 ACK 與測試命令分開計數。
- 每個測試命令只 dispatch 一次；其後 status、observation、capabilities 均不重播。
- 部分 ACK 同時涵蓋 synthetic peer 未套用及已套用命令；原 cache true／false／null
  不會被部分收據覆寫。沒有舊 ACK 時也不新增 VIDEO_RECORDING observed evidence。
- 另外六個 scope／transport 測試確認 GET、DELETE、near-match 路徑不被擴大攔截，
  POST／PUT 的既有 transport body-deadline 與 redirect policy 不變。

## 回歸與獨立覆核

- 既有 CCAPI、modern recording lifecycle、readback recovery、generic partial ACK、
  focus ACK、body-budget：508 passed。
- PC recording lifecycle Node：142 passed；recording owner：12 passed。未修改 UI source。
- 獨立覆核另行執行 70 新測試＋192 lifecycle＋13 readback：275 passed，未發現阻擋問題。
- Ruff 與 `git diff --check` 通過。

新測試的 discovery、wire body／header 與相機狀態為 synthetic，API/session/Urllib
使用 production code，沒有外部網路或實體相機。既有 readback 回歸使用本機 HTTP peer。
沒有 browser E2E、native build、實體相機或手機裝置驗證；不宣稱相容性或實機錄影可靠性。

## 重跑與證據

`PYTHONPATH=bridge python -m pytest -o addopts='' -q -p no:cacheprovider bridge/tests/test_ccapi_legacy_recording_receipts.py`

`python scripts/validation/run_legacy_recording_ack_api.py --phase current > green-api.jsonl`

runner 的 `--phase baseline` 只改變預期結果，必須搭配基準 production source。
本地 red API 證據使用基準 `ccapi.py` 的獨立 source snapshot，與 green 使用相同 fixture；
未修改正在驗證的工作樹來模擬 red。原始結果、來源與 log 的 filename／SHA-256
記錄見同名 JSON。未下載或安裝工具。

## Release Assessment

此變更屬 backward-compatible patch：修正已存在相容錄影 API 的錯誤成功回報。
本次沒有查詢遠端 release baseline 或調整版本。僅 implemented；
PR-ready、main-accepted、preview-released 均未成立。
