# Android 按住 AF-ON

## 範圍與依據

本輪只接通 Android 直連 CCAPI 的按住 AF-ON。協定依據是 [Canon Android sample 的 AF start/stop](https://github.com/Chieri-JN/Intervelo/blob/bb826a1211c49a6d3a569ec8c4b9934b585db270/CCAPI_Sample_Android_1.3.0e/Sample/app/src/main/java/com/canon/ccapisample/RemoteCaptureFragment.java#L477-L484)。該網址為 Canon sample 的第三方鏡像，不是 Canon 官方下載站；未複製 SDK 程式碼或文件。

- 必須實際公告 `POST /shooting/control/af`，沿用公告版本送 `action=start`／`action=stop`。只有半按快門能力時不推定支援持續 AF。
- `heldAutofocusSupported` 是 Android backend 的細項能力，預設為 false；USB、Bridge、簡化 Simulator preset 及離線預覽不顯示未接通的按住功能。Canon 協定 fixture 可使用 HTTP preset 驗證。
- 原有短按 autofocus、half-press 與對焦馬達微調保留。PC、iOS 及 Android USB／Bridge 的按住操作不在本輪範圍。
- 不修改機身 One-Shot／Servo、AF method、tracking 或鏡頭設定。HTTP 成功只表示命令被接受，不表示已合焦，也不保證持續追蹤效果。

## 操作與釋放

取景右下方新增固定 56 dp AF-ON 控制，與放大按鈕橫向分開，僅內容隨方向旋轉。手指按下即開始，抬起、移出取消、手勢取消、控制元件移除、視窗失焦、隱藏 HUD、開設定、離開控制頁、關閉遠端取景或切背景皆要求停止。返回前景不會自行重新啟動 AF。

無障礙與鍵盤的一般啟用動作仍執行有界短按，不建立無法放開的持續命令。滑鼠 hover 可顯示 tooltip；長按 tooltip 不攔截 AF 持有手勢。

開始回應尚未返回時放開，會在回應或失敗後執行 stop。開始失敗也會嘗試 stop，因為相機可能已執行但回應遺失。正常持有自開始確認後最多等待 30 秒便要求停止；這是 App 內的保護，不是相機端租約，不能保證程序被系統強殺或網路中斷後的實體釋放。

停止使用不可取消的清理流程。失敗時保留停止責任、顯示繁中／英文警告與 stop-only 重試入口，阻擋新的對焦、拍攝、錄影切換及曝光設定。斷線／ViewModel 結束先等待 AF 清理，再結束連線。若重試仍因網路不可用而失敗，仍不能宣稱相機已停止，需操作者確認機身。

持有期間暫停其他對焦、拍攝、錄影切換、曝光設定及放大操作；本輪使用方式為放開 AF-ON 後拍攝，尚未實作按住 AF-ON 同時按 App 快門。相機回報的 AF metadata 仍可獨立讀取，綠框只採用相機的合焦狀態。

## 驗證狀態

- Android 單元測試 494 項、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 通過。
- 新增測試涵蓋取消仍停止、開始失敗後停止、停止失敗保留重試、原始例外保留、公告版本路徑及缺少原生 AF 能力拒絕。
- HTC U24 Pro／API 34 分組驗證共 25 項 instrumentation 通過：16 項 lifecycle／metadata、3 項 AF 框 rendering、3 項既有框線／Bulb 回歸、3 項新增手勢／能力／排版測試。首輪兩個測試錯把實際觸碰當成語意啟用、以及未結束測試注入的觸碰；修正後新增的 3 項 UI 測試全數通過。
- 截圖檢查發現 TooltipBox 內層定位不適合承接父層對齊，已用獨立外層定位修正。360×800、800×360、800×1280、1.5 倍字體與繁中，以及四個旋轉角度驗證 AF 控制不碰到頂部狀態列、曝光列或放大按鈕；三種尺寸截圖已檢查。這只驗證新增 AF 控制，不宣稱整個 App 的所有長文字均無裁切。
- 使用獨立 `dev.openeos.control.debug` 測試 APK，未覆蓋既有 Preview，未操作其他專案的 emulator。局部測試 APK 不等於正式發布版本。
- 本輪沒有向實體相機發送 AF、快門、拍攝設定或其他操作命令。操作者尚無法比較機身半按結果，「連 App 後容易失焦」的原因仍未確認。

## Release Assessment

基線為已發布的 `v0.7.0` Development Preview。本輪是新增使用者可操作能力，版本影響為 `minor`，不是 stable；功能 PR 不更改版號或自行建立 tag。待真機按住／放開、不同機身 AF 模式與程序強殺情境另行驗收，不宣稱 Camera Connect 功能等價。
