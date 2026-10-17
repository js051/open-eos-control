# G08／G10：原始媒體 DELETE 部分回應收據

## 範圍與結論

在整合基準 `f7b5f44091f5f975cc3eb7073f647e1c7f9733e9`，Bridge 與 Android 會將帶有
`Content-Range` 的空 HTTP 204 當作刪除完成。這會先移除快取／目前媒體列，即使後續重新列舉仍能看到合成目標。

修正只增加兩個生產判斷：

- Bridge `CcapiSession.delete_media`：在既有 204 完成條件中拒絕任何大小寫的 `Content-Range` 標頭。
- Android `executeMediaDeleteHttp`：在既有 204 framing 條件中拒絕該標頭，包含空值。

`Content-Length`、body、transfer-encoding、status、一次傳送、原 session／listing owner、選取 token 與讀回語意不變。
非 2xx 拒絕分類保持原樣，包括帶 `Content-Range` 的 403 仍為 REJECTED；本批沒有重新定義它。
PC 和 iOS 生產碼均未修改。iOS 原本已在 204 完成判斷拒絕該標頭。

## 真實 Bridge API → 未修改 PC 函式

`prove_media_delete_receipts.py` 透過實際 FastAPI route、session、`UrllibCcapiTransport` 產生 API 結果。
只有 HTTP opener 與相機儲存狀態是記憶體合成 fixture，沒有實體相機、網路或真實媒體。
`prove_pc_delete_feedback.mjs` 將該結果送入從 production `app.js` 原樣擷取的
`deleteMedia`、`runMediaDelete`、`sendMediaDelete`、`applyConfirmedMediaDelete` 及其協助函式，並使用原始
`media-delete.js`。DOM/render、confirmation、fetch adapter 為合成；不是完整瀏覽器驗收。

七個案例：

1. 204 + 一般 `Content-Range`
2. 204 + 空 `Content-Range`
3. 204 + 大小寫混合 `Content-Range`、`Content-Length: 0`
4. 完整空 204，無 Content-Length
5. 完整空 204，Content-Length 為 0
6. 206
7. 403

前三例基準誤回 API 204，PC 回報 confirmed、移除目標、消耗選取 token 並抑制 pending listing 中的目標，
但合成目標仍存在且 fresh listing 仍返回它。修正後同三例 API 502／PC uncertain，目標、選取及 listing 不遭移除。
兩個完整 204 控制組仍成功，206 仍 uncertain，403 仍 rejected；每例只有一次 DELETE，另一個媒體列保持存在。

## 自動化證據

- 最終 Bridge 新測試對基準：6 failed、37 passed；六個失敗僅為三個部分 204 的 session／API 路徑。
- 修正後 Bridge receipt 與完整 `test_ccapi.py`：188 passed。
- 既有 PC deletion ownership／listing ownership：48 passed。
- Android offline JVM host：基準恰好 3／10 失敗；修正後 10／10 通過。
- 正式 Android MockWebServer 測試已新增，**NOT_RUN**；本次沒有執行 Gradle、安裝或下載。
- 實體相機、Android 原生 build／裝置、完整瀏覽器：**NOT_RUN**。

Android host 使用現有且驗證 checksum 的 Kotlin／JUnit／coroutines jars；每個 JVM 限制 256 MiB heap、1 CPU。
編譯完整 production `MediaDeleteHttp.kt`、`MediaDeleteEvidence.kt`，原樣擷取 `CcapiClient.deleteMedia` 與
`CameraViewModel.withDeletedMedia`，並確認後兩個函式與基準位元相同。
HTTP／Okio、model 及連接到 UI mutation 的 confirmation callback 為合成，沒有完整 ViewModel／repository／
OkHttp／socket／Android lifecycle 驗證。十例包含上述部分回應、完整控制組、非空 body、保留 ranged403 分類，以及
owner 在 dispatch 前拒絕時零次傳送。

## 重跑

從 repo root 使用已存在的 Bridge Python 環境，設定 `PYTHONDONTWRITEBYTECODE=1`、`PYTHONPATH=$PWD/bridge`：

```sh
python -m pytest bridge/tests/test_media_delete_receipts.py bridge/tests/test_ccapi.py
python scripts/validation/prove_media_delete_receipts.py --phase current > api.jsonl
node scripts/validation/prove_pc_delete_feedback.mjs api.jsonl
node --test bridge/tests/media-delete-ownership.test.js bridge/tests/media-listing-owner.test.js
python scripts/validation/run_android_media_delete_host.py --jars EXISTING_VERIFIED_JARS \
  --out LOCAL_EVIDENCE/current/android --phase current
python scripts/validation/run_android_media_delete_host.py --jars EXISTING_VERIFIED_JARS \
  --out LOCAL_EVIDENCE/baseline/android --phase baseline --ref f7b5f440
```

API `--phase baseline` 只驗證基準行為，不會自動切換來源；必須在基準 checkout 執行。
各 host receipt 分別記錄 source path／sha256、擷取函式 hash、fixture hash、工具 hash、資源限制及測試邊界。

## 交付與版本評估

目前僅 local implemented；未推送、未開／改 PR、未合併或發布。建議影響為 patch，理由是修復既有媒體刪除的錯誤完成回報。
本批基準 checkout 宣告版本為 0.13.0，沒有重新查詢遠端最新 release，亦沒有調整版本。
Native／實機證據仍待取得，不能據此聲稱特定 Canon 型號或 transport 已通過。
