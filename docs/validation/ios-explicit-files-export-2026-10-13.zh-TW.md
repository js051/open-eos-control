# iOS 已準備原檔的明確「檔案」匯出

## 範圍與操作

本群補上獨立的「匯出至檔案」動作：單張已準備原檔（圖庫卡片與預覽）、批次中的已準備原檔、離線歷史紀錄的單份／全部可用副本，以及前景準備停止後仍有效的暫存原檔。每次均由使用者開啟原生 Files picker 並選擇目的地，採複製模式；原有分享入口及準備紀錄語意保留。

入口只取得既有 immutable payload／本機保留租約。不啟動相機下載、清單讀取、前景準備、Photos 權限或自動重試；使用者選擇的系統檔案服務仍可能自行連網或同步。沒有目的地 bookmark、保存授權、目的地路徑紀錄、自動覆寫／改名策略或每檔成功對應。相同檔名的處理由目的地服務決定。

每次匯出以獨立 UUID 表示；只有本次 picker 的第一個終態可更新該畫面的結果。準備成功、一般分享、匯出 picker 回報及外部 bytes／雲端同步是不同證據。精確正數回傳數量只顯示「iOS 已回報匯出目的地」；空、少、多或重複目的地不推算各檔成功。取消仍提醒可能已有副本，沒有回呼的關閉／程式化 teardown 顯示結果不明。任何未知結果都要求先查看目的地，再明確開始另一輪。

結果只留在目前畫面記憶體，關閉畫面或重啟不恢復匯出結果。重新開啟的 prepared 歷史列只表示原檔曾在 App 內準備；沒有結果不能推論從未匯出或可安全重複。英文／繁中說明均直接告知此限制。

## Apple 契約與選型

2026-10-13 查閱官方文件後使用 `UIDocumentPickerViewController(forExporting:asCopy: true)`，不將一般 activity completion 改成保存證明。

- [複製匯出 initializer](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller/init(forexporting:ascopy:)) 接受來源 URL 陣列與複製選項。系統提供使用者選擇目的地的流程，來源仍由 App 的不可變租約保留。
- [didPickDocumentsAt](https://developer.apple.com/documentation/uikit/uidocumentpickerdelegate/documentpicker(_:didpickdocumentsat:)) 對 export 模式描述為目的地的新副本 URL。文件沒有提供與輸入陣列逐項對應的順序、每項錯誤／部分交易、硬體耐久或雲端同步完成保證。本實作僅採用回傳數量及是否重複的有限觀察；不猜每檔結果。
- [取消回呼](https://developer.apple.com/documentation/uikit/uidocumentpickerdelegate/documentpickerwascancelled(_:)) 表示使用者取消；沒有額外推論目的地零副本。[adaptive dismissal](https://developer.apple.com/documentation/uikit/uiadaptivepresentationcontrollerdelegate/presentationcontrollerdiddismiss(_:)) 不涵蓋程式化 dismissal，故另由綁定關閉與 representable dismantle 提供保守 unknown 收尾。
- 現代 delegate 文件統稱回傳 URL 為 security-scoped；[舊版 export 指引](https://developer.apple.com/library/archive/documentation/FileManagement/Conceptual/DocumentPickerProgrammingGuide/AccessingDocuments/AccessingDocuments.html) 又明列匯出 URL 只作成功信號、App 不可讀取。此差異不影響選型：App 不保存、不開啟、不列舉、不取得 security scope、不建立 bookmark，也不驗讀目的地 URL，完全不依賴外部讀取權限。

## 來源與生命週期

`MediaBatchExportPayload` 原有 owned/stored 強租約保留。暫存來源在 payload 建立時捕捉有界 metadata fingerprint；歷史租約直接攜帶 manifest 已驗證的來源身分，不能在跨 actor 回覆延遲後把替換檔重新當成基準。匯出 admission 與真正交給 UIKit 前都重新檢查一般檔案、正長度、單一 hardlink、安全無 symlink 路徑，以及 device/inode/size/mtime/ctime。

失去／變更／不安全來源拒絕整次 Files presentation，不部分交付、不重抓。metadata 核對不等於內容 hash；UIKit 接受 URL 後也不是持有 App 提供的檔案 descriptor，無法宣稱能防止外部程序在最後檢查之後改寫資料。已知 App 路徑只依其既有不可變檔案／租約規則操作。

實際 `UIDocumentPickerViewController` 子類強持有 coordinator，coordinator 強持有原始 payload；SwiftUI dismantle 或相機斷線不能提前釋放系統仍可能使用的來源。只在 UIKit 控制器及其 coordinator 都真正釋放後，既有 owned-file／stored-lease 清理才可刪除自己的暫存或 clear tombstone。清除歷史仍只處理 App 副本，不影響外部目的地；晚到回呼不重建 prepared 紀錄。

picker 回呼、綁定關閉及 teardown 均攜帶固定 attempt UUID。尚未完成 teardown 時不接受另一個 picker；重複／已退休 coordinator 沒有更新或終止現任嘗試的權限。相機 session 變更不撤銷已由 Files picker 取得的本機來源。

## 驗證與尚未執行

新增 `MediaFilesExportTests` 的 17 個 source test methods：精確／空／不足／超額／負數回傳、重複目的地、不同目的地名稱與順序、取消後晚到結果、成功回報後取消、互動關閉、程式 teardown、尚未建立 coordinator 即取消、舊綁定／舊回呼／舊 teardown 對新嘗試、重複 coordinator、缺檔／零長度、同長度替換／編輯／截短、symlink／hardlink、admission 後變更、UIKit 真正持有期間的租約及重開畫面不自動重試。這些僅呼叫受控 delegate／model，不呈現 picker 或匯出真檔案。

既有 App production-client 整合 fixtures 新增 5 個方法，涵蓋單張、批次、離線歷史及前景暫存四種來源；檢查沒有額外相機請求、disconnect／clear 不搶先刪除 picker 來源、準備事實不被匯出結果改寫，以及 store acquisition 回覆延遲期間來源被同長度原子替換時拒絕匯出。共新增 22 個 App 測試方法。另擴充 1 個既有 canonical UI 旅程，補上批次／離線歷史 Files 按鈕可達、44pt、分享仍可用及冷啟動不捏造匯出結果的 source assertions。

本機已執行：`git diff --check`；10 個新增英／繁中 key、格式參數位置與 call-site 覆蓋核對；XcodeGen App／Tests／UITests 自動 discovery 與 Info.plist 解析；copy mode 及無相機下載／Photos／bookmark／目的地讀取 API 的聚焦來源檢查。

本環境未發現 `swift`、`xcodebuild`、`xcrun`，沒有安裝工具鏈。因此 Swift 編譯、XCTest、Simulator／UI、真正 Files provider、iCloud／第三方同步、實體 iPhone／iPad、相機驗證及 exact-head CI 均 **NOT_RUN**。新增測試方法與來源審查不得當成 runtime 通過。既有其他 head 的 CI 不適用本群。

後續原平台驗證須涵蓋英／繁中、大字級、iPhone／iPad、On My iPhone／iCloud／第三方 provider、重名與改名、部分／取消／拒絕／磁碟不足／provider 終止、切背景、picker 開啟期間清除歷史或斷線、重入／重開及再次手動匯出；以目的地實際 bytes 另證保存，不能用本 App 回呼代替。

## Release Assessment

本 checkout 最新已記錄發行基準為 v0.13.0 Development Preview；此群為向後相容、可見的獨立 iOS Files 匯出能力，impact `minor`。不改版本、不自行合併或發行。原平台編譯／測試／必要 exact-head CI 未執行，實體裝置與外部 provider 未驗。此狀態是來源實作候選，非 PR ready、main accepted 或 released，也不宣稱全部 P2／全平台交付已驗收。
