# Bridge／Android 拍攝設定完整收據

## 原有缺口與範圍

基線 `faefa520` 的建立資料夾、檔名設定已驗 JSON／body 長度，但仍接受 HTTP 206 或帶 Content-Range 的寫入回覆。實際 Bridge API 路徑會將未套用的 partial 目錄回覆回傳為 200 `{name:ABCDE}`；檔名設定雖因完整讀回不符回傳 502，卻仍帶錯誤的 `stage=acknowledged`。

本批僅新增九行產品 gate：Bridge `_request_capture_configuration_json` 在原非 2xx 處理之後，針對非 GET／HEAD 拒絕 206 或任何大小寫的 Content-Range；Android `requestJson` 在 `captureResponse`／`acknowledge` 前做同一判斷，包含空 header。原合法 JSON、body framing、其他已接受成功狀態、未知寫入、owner、deadline、單次方法／路徑、readback、明確恢復流程都保留。Bridge 維護時間寫入原已有更嚴格 gate，無新增可接受行為。

目錄仍是五字元 suffix；相機的完整資料夾名稱含三位數前綴。完整 ACK 後的目錄讀回只更新狀態，不新增自動選取、建立名稱相等或選取名稱相等條件。iOS 原有 gate 已嚴格，本批未改 iOS。沒有新功能、寫入重試、路由或發布操作。

## 已執行

- Bridge 新增真 FastAPI → session → Urllib transport 測試，只有相機 HTTP peer 與 discovery 為 synthetic，沒有網路請求。相同測試在基線為 **6 failed／9 passed**；修正後新增加原 capture-configuration 為 **35/35 PASS**。
- 六個 red 覆蓋兩種 mutation 的 206、200+range、200+空 mixed-case range；不得回報 ACK、不得自動讀回或重送。使用者明確恢復仍只 GET。完整 200／201／202、既有 suffix／readback 語意及非 2xx 錯誤分類為相容性控制，不宣稱 Canon 保證每種狀態。
- Bridge 新增測試加既有 CCAPI、capture-configuration、shooting-settings、maintenance、maintenance restore、recording、recording recovery，共 **545/545 PASS**。Ruff 與 git diff whitespace 檢查通過。
- Android offline host 逐位元抽取真 `createDirectory`、`setFileNaming`、`postJson`、`putJson`、`requestJson`，搭配完整 production evidence、strict JSON/body readers、命名 model。基線 compile 0／JUnit 1，指定 **6 個真正 assertion red／6 pass**；修正後 compile 0／JUnit 0，**12/12 PASS**。
- Host 控制包含 complete 200／201／202、directory simulator 的 partial gate、原 naming mismatch 保留 ACK、readback unavailable、不完整／重複／尾端 JSON、長度不符、非 2xx 原分類、無關 GET range，以及取消／owner 退役的原 dispatch fence。Call 建構不代表 dispatch；取消案驗證 execute 次數為零。
- 所有 JVM 執行為 256 MB heap／1 active processor，沿用 checksum 已驗證的本地工具；無 Gradle、下載或安裝。
- 完整 `CcapiClient.kt` 與 `CcapiClientTest.kt` 的 PSI syntax 為 2 files／0 errors。此項不是 Android type-check。

## 證據界線與未執行

Android host 的 HTTP Call／Response／InputStream、discovery、readback loader 與 typed JSON decoder 是明示替身；StrictMediaJson 仍驗證實際序列化 bytes。這不等於真 OkHttp／socket／Android／UI 或實機驗收。

`CcapiClientTest` 新增三個真 MockWebServer 測試：directory real／simulator partial matrix、naming partial matrix、原成功狀態相容性。均為 **AUTHORED_NOT_RUN**。完整 Android type-check／native tests、exact-SHA CI 與實體相機均 **NOT_RUN**；沒有把 source review 或 host PASS 當成這些階段。

獨立審查已確認九行產品 diff 的 scope 與 ACK 邊界，未發現 blocker。完整回歸與 [文字收據](evidence/capture-config-receipts-20261017/) 包含來源、fixture、工具 hash 與退出碼；不提交可重建 jar。

## 重現與 release assessment

Bridge：以專案 bridge 為 PYTHONPATH，執行 `python -m pytest bridge/tests/test_capture_configuration_partial_ack.py bridge/tests/test_capture_configuration.py -q -o addopts=`。完整相關回歸檔案列於 receipt.json。

Android：`python scripts/validation/run_android_capture_config_receipt_host.py --jars <既有已驗證工具目錄> --out <輸出> --phase baseline --ref faefa520`，再以不同輸出執行 `--phase current`。runner 要求基線指定 runtime failure，而非將編譯失敗當 red。

Release impact：**patch**，修正既有拍攝設定 mutation 的假 ACK。狀態為 implemented，待整合與 exact-SHA CI；本批不改版號、不 push、不合併 main、不發布。既有物理相機未驗限制保留。
