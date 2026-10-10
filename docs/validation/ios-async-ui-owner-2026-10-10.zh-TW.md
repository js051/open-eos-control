# iOS 非同步介面結果的操作歸屬

## 基底與目的

本批從已驗收 main `ce2e3bc561ff422ad83e1279f98a580ddd6d5f6b` 準備。其 tree 為 `2c970f10fe7a69562319ad291fa2b792adce8165`，PR233 的 main acceptance 為 `38035961116`。本批目前是本地已實作、待正常 CI 執行；不能沿用 PR233 的綠燈作為新程式的驗收。

同一個畫面上，較早開始的非同步工作不應覆寫使用者後來的選擇。本批收斂三個既有路徑：

- Bridge 掃描：URL、token 或連線模式變更，以及 disconnect，都使舊掃描失效。即使設定 A→B→A，舊結果、錯誤與 defer 也不能覆寫新列表、選項、錯誤或新掃描的 busy 狀態。同設定重複呼叫仍保留原本忙碌排除行為。
- 對焦標記：新的標記取消舊計時 Task 並換唯一身分，防止兩次座標與 accepted 完全相同時，舊的到期工作提前移除新標記。disconnect 同樣退休標記工作。
- LUT 匯入：每次匯入、Clear 與檔案選擇器失敗都是新的選擇。較舊讀檔／解析的成功或錯誤不能覆寫它。LUT 是應用程式層的監看設定，因此相機 disconnect 不會退休 LUT 選擇或清除已套用 LUT。

## 預設路徑與限制

三個預設為 nil 的非同步依賴入口，讓測試可以明確控制回覆順序；一般 App 繼續使用原 DesktopBridgeClient、原 security-scoped LUT 讀取及原 1.2 秒標記延遲。LUT 的 16 MiB 大小界線、UTF-8 驗證、parseCubeLut 與 security scope defer 清理保留。

此批不新增相機操作、持續自動命令或傳輸重試。測試 fixture 是合成資料；不宣稱實體相機、iPhone 或檔案 provider 已驗證。取消或過期的讀取若底層不配合取消，仍可能執行到其原本終態；本批保證它無法發布失效結果，未宣稱所有底層 I/O 都可立即停止。

## 驗證狀態

- 本地 Swift compiler／iOS runtime 不可用；沒有本地 XCTest 通過聲明。
- 新增 18 個 XCTest 方法，涵蓋 60 個 gate 參數情境（Bridge 40、focus 4、LUT 16），另有預設 loader 真正讀取小型暫存 `.cube` 與無效 UTF-8 的控制。每個操作保存精確 task handle；cleanup 取消、排空目前與後續 gate、等待真正完成。沒有以任意 sleep 作為順序證據。
- Production 全 diff 與完整測試（含 fixture、actor gate、cleanup）已由未撰寫本批程式的 reviewer 讀審，無阻擋項。Whitespace 與 `verify-version.py --tag v0.13.0` 通過，這些靜態檢查不等於 XCTest pass。
- 完整候選來源完成後，仍需正常 exact-head CI 與適用的 iOS App／UI gates；不修改必要檢查或測試選擇。

## Release Assessment

基準為 v0.12.0 Development Preview；本批屬 patch，修正既有介面在延遲回覆下的結果歸屬。尚未發版或建立新版本候選。PR219 原 Compose crash 根因 UNKNOWN、PR223 KVM 條件與 frozen v0.13 HOLD 保留，不能由本批測試通過推論解除。
