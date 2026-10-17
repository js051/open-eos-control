# Android 媒體畫面 callback 的原連線 owner

基準：`20251fcdfa80cabafad17c17a19959fce1aa5e8c`。此文件記錄 G08/G09/G10 的窄範圍 caller 修正；不是三端或 Android 原生驗收。

## 可追蹤反例

`MediaScreen.kt` 原本以外層 `rememberUpdatedState` 和 keyed content 隔離媒體選單，單項及批次 metadata mutation 已檢查原 owner。不過同一單項選單的下載、選目的地、Open in Serein，以及 viewer／gallery 的媒體 callback 仍直接呼叫未綁 owner 的 `CameraActions`。

保留 A 連線的選單 callback，重連為 B 後再呼叫舊 `onSaveToFolder`，原路徑會將 A 的 item 傳到 `CameraMediaPickerLaunchers.downloadDocument`。`beginMediaPicker` 此時才擷取 B 的 owner；若 picker 在 B 連線內返回，既有 result-owner 檢查會通過，無法辨認最初是 A 的 callback。Gallery save 和 Serein preparation 也在呼叫後才擷取目前連線。相同 item ID 不表示相同相機媒體。

這是 production caller 的來源反例；本輪沒有在 Compose 裝置上觀察真實輸入排程。既有 metadata UI 測試已有保留 semantics callback、替換 owner 後呼叫的相同回歸模型。

## 修正

- `MediaScreenActionGate` 在執行當下比對原連線的 reference identity 和 session generation，讀取的目前 owner 仍由 keyed content 外層提供。
- `MediaScreenContent` 對傳給子畫面的 actions 建立綁定 owner 的副本，保護 document/folder/Gallery/Serein、upload/cancel、listing refresh/cancel、preview open/adjacent/close 和 thumbnail load/disposal，共 14 個 callback。
- 舊 callback 不能替新連線取得 picker／transfer owner，也不能關閉新 viewer、取消新下載或新 thumbnail。相同 owner 仍直接傳遞原參數，不增加重試或延後執行。
- 既有 metadata mutation、picker result-owner、下載接續與 transport 能力限制保持原契約。沒有新增 transport、安裝依賴或繞過相機能力判斷。
- 已儲存 Gallery JPEG 的獨立 `openSavedJpegs`／`sendSavedJpegs` 流程沒有綁入相機 owner gate，斷線後仍使用既有本機交付流程。

## 已執行證據

1. 使用既存、SHA-256 對照原安裝 receipt 的離線 Kotlin 2.2.21／JUnit 4.13.2 工具。沒有下載或修改共用 cache。
2. Kotlin/JUnit：22/22。完整 production `MediaScreenActionGate`、`MediaMetadataBatch` 和 metadata evidence 檔案直接編譯；6 個新 owner-gate 測試、14 個既有 metadata batch 回歸、2 個畫面 action-binding 測試。
3. 兩個 binding 測試編譯 `MediaScreen.kt` 的逐 byte 原樣 action-binding 宣告，確認全部 14 個 callback 的正常參數／順序，以及舊 owner 全部拒絕而新 owner 仍可執行。外圍 `CameraActions`／`CameraUiState` 使用只含所需欄位的 shape adapter，媒體參數使用合成字串 ID；沒有編譯完整 Compose 畫面或 Android ViewModel。
4. 來源 wiring guards 13/13，包含 4 個新 guard 與 9 個既有 metadata mutation guard；這些是來源斷言，不是 runtime。新增 guard 也確認獨立 saved-Gallery handoff 未被相機 gate 攔截。
5. 四個新增／修改 Kotlin 檔的 PSI syntax check 通過；`git diff --check` 通過。PSI 不提供 Android typecheck 證據。

另已新增 2 個 Compose 回歸測試：舊單項選單的三種 transfer callback 與舊 gallery preview callback 必須被拒絕，新畫面的正常操作必須保留。這兩個測試本輪 **NOT_RUN**。

## 狀態與界線

Release impact：**patch**，修復既有媒體操作的 session ownership。此窄範圍已有程式與本地 host 證據；本文件不宣稱 PR ready、main accepted 或 released。

完整 Android Gradle build/unit、Compose/instrumentation、OS picker/provider、實體相機、Serein 外部接收端與真實 callback 排程仍 **NOT_RUN**。原 89 列對帳的 native、真機、Windows/WSL 與未實作 transport 限制不因本修正變成 PASS。
