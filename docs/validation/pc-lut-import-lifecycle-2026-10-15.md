# PC LUT 匯入與移除生命週期驗收（2026-10-15）

狀態：implemented。尚未 commit、push、CI、合併或發版；不代表 PR ready。

基底：`df55c570e525bf044daa45d203687354b941e62f`。

## 實作與使用者結果

- 保留既有 `.cube` 語法、數值、domain、2–64 cube 邊長限制與 monitor-only 用途。
- `File.size` 預檢後，只讀 `slice(0, 16 MiB + 1)`；實際 bytes 再驗上限及完整大小。未知／不合法 metadata 在讀取前拒絕。未呼叫可能無界、非嚴格解碼的 `File.text()`。
- production UTF-8 decoder 使用 fatal mode，非法 bytes（包含 TITLE 或註解內）拒絕；移除單一檔首 UTF-8 BOM，雙 BOM 拒絕，不轉碼 UTF-16。
- 有效選檔同步建立 import owner，最多一個實體 read 與一個最新待處理選擇。B／C 連選時只保留 C；舊成功／失敗不解析、不套用、不清除新 owner。
- 原 LUT 持續套用，直到新 bytes 解碼、既有 parser 及 renderer 初始化成功。當次失敗獨立保留在 import status，不會被逐幀分析清除，也不改無關 monitor error。
- 每次原生 picker 使用獨立 input／ticket。開 picker、null 與使用者取消不取消此前有效匯入；舊 callback 或同步 opening error 不能挪用新 ticket。
- 明確 Cancel import 撤銷 pending 選檔／發布權並保留原 LUT；Remove 同時清除已套 LUT。原生 Blob read 不可中止，UI 明確顯示仍在等待先前讀取退場，不聲稱 OS I/O 已停止。
- 只有成功替換或確實移除已套 LUT 才增加 monitor LUT revision。失敗、取消與空值重複移除不虛增。其他監看設定保留。
- Done／關閉 monitoring dialog、隱藏分頁及 camera disconnect 不撤銷 app/page-owned LUT。pagehide／beforeunload 撤銷 pending 與 picker，舊 read 不可重新建立 renderer、發布或重啟 queue；pageshow 不自動恢復已取消匯入。

## 隱私補正前的本地影響範圍證據

2026-10-15 20:54:52–20:55:05 UTC，以既有 Node v24.19.0、Python 3.12.14、ruff 0.17.0 執行；共用 heavy lock 與 `taskset -c 8`，沒有安裝、network 或 npm/npx。

- 盤點 55 個 package module scripts 加 1 個未列入 package 的現有 date/review script。選取 30 個 app-production-handler 與 LUT/render/analysis 受影響腳本，直接執行 `node --test --test-concurrency=1`：1,269 tests，1,269 pass，0 fail。
- 新增 `bridge/tests/lut-import-lifecycle.test.js` 45 cases，包含共同 16 組 UTF-8 bytes、16 MiB／超限 reader 邊界、最新選擇與有界 queue、舊成功／錯誤、取消／移除、picker null／cancel／late callback／opening throw、renderer failure、page teardown、Done／hidden／session replacement、其他 monitor settings 保留。
- `python -m pytest bridge/tests/test_static_ui.py`：7 passed。
- `ruff check bridge/tests/test_static_ui.py`、app/lut `node --check`、`git diff --check` 與測前／測後 SHA-256 複核：exit 0。
- 修齊兩個既有共享 lifecycle fixtures：media-auto-import-owner 補已存在的 `stopRtpAudio` collaborator；media-upload-owner 改為執行真 pagehide callback，仍驗證恰一次 silent/retire upload cancellation 且在 maintenance 後，避免把 unrelated cleanup 必須排第一行當契約。沒有修改 audio production 或其封存測試群。

### 隱私補正前來源 SHA-256（20:55）

- app.js：`d48c64cb8db7b9c95ccc83e370dd67bcb08ece67242f1d5d753b9c598332da87`
- lut.js：`54dbd62e844babef91747b4bf23aa51a08a3af2a0aa617abe9d39c3018914ce6`
- 新 lifecycle test：`dd6246bae634e465ae116db6566c70c523b83c6adc50cdc895ee780edc126980`
- 共同 UTF-8 vectors：`570d6f8e59f1300f98ed081b617110b8c13fb365fe5153ab23d6ef8c0cb7baf4`

## 最終隱私補正與限定重驗

20:57 review 發現新增診斷欄位原樣輸出 provider／renderer 錯誤；既有 sanitizer 只能處理部分路徑，無法保證排除 bare filename、TITLE 與任意根路徑。改為 `lutImportFailed` Boolean；本機 UI 仍可顯示詳細錯誤，匯出診斷不包含該原字串。

2026-10-15 20:59:04–20:59:06 UTC，只重驗此最後修改受影響範圍：

- production LUT lifecycle 47 cases（新增 provider／renderer 各一個 filename／TITLE／自訂根路徑／內容 canary）＋既有 diagnostics script：48/48 pass。
- canary 執行真正 `diagnosticReport()` 與 `diagnostics.safeValue()`，驗證本機錯誤保留、匯出只保留 Boolean、其他相機錯誤不被清除。
- Python static 7/7、ruff、app syntax、diff check、測前後 SHA-256 複核：exit 0。
- 最終 app SHA-256：`88f3b9bab5690bdc9a54abb489c912810057fc45d1ca2729a5673a60aaa64fbc`。
- 最終 lifecycle test SHA-256：`44729263c9a5034646bd48609b4e71914cc6e7bb46253ba8c3bc37e47adf9874`。
- lut.js、HTML、package、兩個共享 lifecycle fixture 與共同 bytes fixture 未再變動；其 SHA 與前版相同。最終 production 差異僅診斷欄位由 raw error 改 Boolean，加上解釋註解，匯入／讀取／picker／取消／render 路徑未再變更。

前述 1,269-suite 結果屬 20:55 的前版 app SHA，沒有重跑於最終 app SHA。不能把兩輪測試拼成同一輪最終全集通過。

## 中斷與證據限制

20:39 第一輪 254 Node tests 中 250 pass、4 因 media-auto-import fixture 缺少 collaborator 失敗；原 log 保留。隨後 Python static 只有 START、無終態，明確記為 UNKNOWN。恢復後只先修失敗 fixture 與補驗未完成段；等待／停止中 UI 最終補正後，對最終來源一次執行上述 30-script 影響範圍。

上述是 production-function／Node-controlled DOM、Blob 邊界與 static UI 證據。沒有執行 browser File picker、真 WebGL／GPU／compositor、OS 原生 I/O 取消或實體相機驗證。也沒有宣稱全 repository、native 平台、CI、KVM 快照或相機 transport 全部對齊；這些不在本次本地驗證結果內。
