# 真機驗證

[English](device-validation.md)

自動測試只能證明程式在可重現輸入下的 request、解析、狀態轉換與 UI 契約，不能證明實體相機真的接受操作，或產生預期結果。因此 Open EOS Control 將證據分成三層：

1. **相機公告（Advertised）**：相機或目前 engine 公告必要操作。
2. **本次工作階段已成功（Observed this session）**：產品收到成功的命令回覆或有效資料。
3. **操作人員已確認（Operator-confirmed）**：操作人員明確看到相機端結果，例如新增照片、焦點移動、錄影指示或記憶卡項目已刪除。

只有第三層能宣稱實體結果已確認。驗證工具不會把相機公告冒充成執行成功，也不會把成功回覆自動當成肉眼確認。

## App 內引導式驗證

Android 與 iOS 連接相機後，可進入 **Debug > 真機驗證**；PC 控制介面則在 **診斷** 分頁提供相同流程。只有相機已公告，且目前 backend 在本次工作階段收到成功操作回覆或有效資料的功能才會出現。請在親眼看到實體相機產生預期結果後才勾選。

確認內容只保存在記憶體；斷線、重新連線或進入離線 UI 預覽時會全部清空。模擬器與離線 UI 預覽不能建立真機驗證紀錄。**複製真機驗證紀錄** 會產生可公開的 Markdown，只包含相機型號、transport、advertised／observed／operator-confirmed 狀態，以及對應隱私安全診斷報告的 SHA-256；不包含序號、相機 URL、endpoint 清單、raw status、帳密或本機路徑。PC 控制介面使用瀏覽器原生 Web Crypto，因此只有一般的 localhost 來源或 HTTPS 能啟用此複製操作。

App 內紀錄是方便檢閱的工作階段筆記，不是遠端證明。完整診斷報告仍應留在私密位置，提交真機紀錄前仍須使用下方 verifier 檢查。

## 取得報告

使用目前版本連接實體相機，實際操作要驗證的功能，再從 Android、iOS 或 PC 控制介面的 **Debug > 複製診斷報告** 取得報告。請把原始報告保存在 repository 外，檔名可使用 `diagnostic-report-r6m3.txt` 或 `diagnostic-report-r6m3.json`；Git 已忽略這類檔名。

遇到 CCAPI 韌體或探索問題時，分享私密報告前請保留 `discoveryAttemptCount` 與編號的 `discoveryAttempt` 行。它們能區分根清單、developer 清單與 identity fallback 的結果，但不含回應 body 或值。每一行只會包含固定的相對 endpoint、結果、可選 HTTP 狀態、已過濾的頂層鍵、協定版本、有效操作數與截短狀態；不會包含相機 origin、帳密、Authorization、例外訊息或 raw JSON。

驗證媒體清單時，Android／iOS 請一起查看 `mediaItemCount` 與 `mediaLoadStatus`，PC 請查看 `mediaLibrary.itemCount` 與 `mediaLibrary.loadStatus`。只有 `COMPLETE` 代表目前數量來自已走完相機公告之全部容器與分頁的遍歷；`LOADING`、`CANCELLED`、`FAILED` 與 `NOT_LOADED` 都是刻意標示的不完整證據。產品沒有 500 筆媒體上限。

Android 與 iOS 也會在媒體畫面顯示逐步收到的數量，並可取消仍在執行的相機遍歷。PC Bridge 會誠實顯示載入中或失敗後保留的舊數量，但目前 `/media` 請求是同步執行，因此不提供只會中止瀏覽器請求、卻無法停止引擎工作的假取消按鈕。

原始報告應維持私密，且只有符合以下條件才會被接受：

- 使用 report schema 1，並包含明確的產品版本；
- 型號為 Canon EOS R6 Mark III，產生時間是含時區的 ISO-8601；
- advertised、observed、validated 與兩個差集的數量及內容完全一致；
- 能力證據未截斷，除非使用者明確允許；
- 不包含相機序號、帳密、Authorization、email、URL user info 或本機絕對路徑；
- 預設不超過 30 天，且時間不得比目前時間超前五分鐘以上。

## 驗證必要能力

以下範例只接受直接 Android CCAPI 報告，並要求相機身分與有效 Live View 資料都曾在本次工作階段成功：

```powershell
python scripts/validation/verify_diagnostic_report.py diagnostic-report-r6m3.txt `
  --expect-transport CCAPI_NETWORK `
  --require CAMERA_IDENTITY,LIVE_VIEW `
  --format summary
```

只有公告、尚未成功執行的功能無法通過 `--require`。例如相機即使公告快門 endpoint，也不能直接把 `STILL_CAPTURE` 算成通過。

可以重複使用 `--expect-transport` 接受多條路徑。目前報告值包括 `CCAPI_NETWORK`、`USB_PTP`、`DESKTOP_BRIDGE`、`DESKTOP_BRIDGE_LIBGPHOTO2` 與 `DESKTOP_BRIDGE_CCAPI`。

## 記錄實體結果

親眼確認相機端結果後，才加入操作人員確認。`--require-physical` 會讓遺漏確認直接失敗：

```powershell
python scripts/validation/verify_diagnostic_report.py diagnostic-report-r6m3.txt `
  --require STILL_CAPTURE `
  --physical-confirmed STILL_CAPTURE `
  --require-physical STILL_CAPTURE `
  --format markdown `
  --output docs/validation/eos-r6-mark-iii-still-capture.md
```

產生的 Markdown 只包含型號、產品版本、transport、時間、能力狀態、確認標示，以及已經過隱私檢查之來源報告的 SHA-256。它不會輸出 raw endpoint、raw status、帳密、序號或本機路徑。這個 hash 只能識別完全相同的私密來源報告，不是簽章或遠端證明。既有輸出檔不會被覆寫，除非加上 `--force`。

操作人員確認屬於明確的人工證詞，不是自動量測。若測試的是持續 FPS、延遲、檔案完整性或 Wi-Fi／行動網路並存，仍應在驗證紀錄旁補上實際環境、測量結果與限制。

## 自動化輸出

使用 `--format json` 可取得機器可讀結果。exit code 0 表示 schema、隱私、時效、一致性、必要能力與必要實體確認全部通過；無效或不足的證據會以 exit code 2 結束，並將每項失敗原因輸出到 standard error。

本機可執行：

```powershell
python -m unittest discover -s scripts/validation/tests -p "test_*.py"
```

CI 也會執行相同測試。verifier 測試通過只能證明驗證工具本身，不能證明相機能力；實體來源報告與明確人工確認仍不可省略。
