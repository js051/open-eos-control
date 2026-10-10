# iOS 已載入素材多選與循序原檔交付

## 範圍與來源

延續同一 iOS 挑片開發批次，基底為本地 e9c94c0（與 PR236 的 39c01b6 完整 tree 7e89d6ab 相同）。日期、評分、資料夾、媒體類型與載入範圍保持既有語意；本批增加選取、循序準備原檔、部分結果與原生分享入口。公開內容只使用既有 EOS CCAPI／Desktop Bridge 契約，沒有私有消費端資訊、額外 endpoint 或為選取隱式載入其他頁面。

## 使用者流程與結果語意

- 每張素材卡有獨立選取控制，不改變預覽項目；可一次選取篩選後已載入項目或清除選取。
- 選取使用 opaque ID 的 UTF-8 身分與穩定順序。篩選隱藏、清單省略與目前未載入的選取保留，數量分別顯示；明確刪除成功或 session 重設才移除相應選取。
- 按「準備所選原檔」固定項目快照與連線身分，一次一檔使用既有原檔下載與完整性檢查。離線預覽只支援選取，不產生虛構下載。
- 第一個失敗或取消停止後續項目。結果分開標示未開始、準備中、App 內已備妥、失敗、已取消或結果未確認。取消不宣稱相機端 I/O 立即停止。
- 已備妥項目可以一起交給 iOS 原生分享介面，使用者再明確選擇目的地。沒有自動 Photos／Files 寫入或權限接受。系統 activity 的完成回覆不是外部檔案保存或 fsync 證據。
- 本機每個原檔位於原子新建的 UUID 目錄，檔名經界線與長度處理。同名原檔不互相覆寫本機副本；接收端如何處理同名仍取決於使用者選擇的 provider，畫面明示限制。

## Ownership 與檔案生命週期

批次 token、session generation／session 值、每項 token 一起檢查進度與完成回覆。disconnect 同步退休舊 owner；舊回覆或 defer 不能清除新批次。取消不合作的 transfer 必須結束後才釋放其媒體 busy；不啟動後續下載。每個暫存 lease 只清理由自身原子建立的目錄，失敗不接管任意路徑。原生 activity 的 immutable payload／coordinator 強持有已備妥檔案，即使 camera reset 或另批開始仍維持分享來源；presentation 與狀態不再持有後才清理。

## 驗證與未執行

- 新增 14 個受控 App 測試方法，走 production CCAPI／Bridge client：精確 Unicode 身分及 metadata／評分／保護／刪除回覆對兩個等價拼寫 ID 的隔離、RAW/JPEG 分別選取、部分清單與 60/61 邊界、同 session 依序成功與失敗、取消各階段、不合作回覆、reconnect 同 wire ID、晚進度、同名檔、原子目錄碰撞與既有位元組保護、分享 lease 經 reset／新批次存活、busy 與離線控制。
- 新增兩個正常 UI 測試來源：英／繁中大字體離線選取不冒充下載，以及 synthetic Canon 兩原檔備妥／分享入口可達／沒有拍攝。
- 此雲端沒有 Swift／Xcode，編譯、單元與 UI runtime **NOT_RUN**；来源審查與 git diff --check 不代替執行。交付後以 exact-head 正常 CI 記錄結果，不沿用前版測試。
- 新 UI 旅程只驗分享入口可用，尚未執行 UIKit share sheet 的呈現／關閉／再次開啟（包括 iPad）；不選外部分享目的地，不宣稱已保存；完整 App 人工、真相機與 Files／Photos provider 驗收依使用者指示開發後集中。
- 不含跨程序續傳、背景 transfer、Range resume、持久化隊列、批次刪除／評分／相機寫入。原 required CI、安全檢查與 release HOLD 保留，不將本批稱為整個 P2 或完整產品驗收完成。
