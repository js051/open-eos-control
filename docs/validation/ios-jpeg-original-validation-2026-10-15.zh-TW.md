# iOS 自動原件 JPEG 驗真與失敗後恢復

## 範圍與交付狀態

本批僅處理 direct CCAPI 前景自動 JPEG 原件匯入。手動 JPEG／RAW 下載、Bridge 手動相簿及其 fallback 不變。既有兩次完整 baseline、舊 ID 排除、兩次新鮮且相同正數大小、精確 ID／名稱／種類／MIME、owner／取消邊界、單次 canonical GET 均保留。沒有新增相機請求、shutter 重送、背景重試或自動恢復。

程式與聚焦 XCTest 已實作；本機沒有 Swift／Xcode／ImageIO，Swift typecheck、App build、XCTest、ImageIO 實際解碼與實體裝置驗收均為 NOT_RUN。來源檢查通過不代表這些階段通過，也不代表 PR ready、main accepted 或 preview released。未 push、未觸發 CI、未合併或發版。

## 發佈前驗證

1. 新鮮檔案大小超過 64 MiB，在原件 GET 前拒絕。既有 HTTP 200、無 Content-Range、傳輸完整性與精確大小仍先驗。
2. response Content-Type 與 metadata MIME 是獨立證據。保留 transport 暴露的大小寫鍵值，於不分大小寫查找合併前蒐集每個 Content-Type。缺席可接受；present 空字串、僅空白、重複值、comma 合併值、控制字元、非 ASCII、其他型別或錯誤參數語法拒絕。允許 image/jpeg 或 application/octet-stream 與有效參數，大小寫及一般空格可正規化。欄位最長 4,096 UTF-8 bytes。HTTPURLResponse 可能先合併或正規化 wire headers；這不是新 raw-wire HTTP parser，不能證明底層已隱藏的欄位。
3. transport 先關閉本次暫存，再交回 client。validator 以 O_NOFOLLOW 的 regular-file descriptor 有界讀取非 mapped snapshot；核對精確大小，讀取／解碼前後核取消，驗證前後核對 inode、裝置、mode、size、mtime、ctime 及原路徑 identity。這是私有暫存的連續性檢查，不宣稱可抵抗所有本機外部競爭者。
4. 結構 parser 限 64 MiB、64,000,000 source pixels、每軸 16,384、4,096 markers、256 scans。一個 8-bit SOF0／SOF1／SOF2，1／3／4 components；量化／Huffman 宣告、引用、component 順序、scan parameters、stuffing、restart placement／順序及非空 entropy 均檢查。APP／COM payload 有界且當作 opaque data。SOI 必須在第零 byte；EOI 必須結束整份檔案，拒絕 trailing junk／拼接。unsupported coding 明確失敗。
5. ImageIO 必須辨識 public.jpeg、單影像、source 與 index statusComplete，source 尺寸須與 parser 相同。強制從原圖產生 thumbnail，要求 longest edge 1,024，實際輸出每軸不得超過 2,048，再以 CGContext 消耗 raster pixels；前後再次檢查完整狀態。沒有只讀 header 就當作解碼，也不使用內嵌 EXIF thumbnail 代替原件。ImageIO 的內部暫存配置與同步 decoder 取消能力不在 App 可完全控制範圍內。
6. 以上成功後才移動原始 bytes、建立 private prepared history，繼而交付 Files。沒有重新編碼、方向轉換或替換原件 bytes。相機沒有提供 checksum，此處不能證明該照片就是相機本來意圖提供的影像。

## 失敗與恢復

無效原件不發佈、不增加 prepared／Files copied 計數，也不進 delivered 結果。只清理本次 transport／App owned 暫存；已保留與已交付 Files 原件不刪除。既有 store 記錄 failed/downloadFailed，這次補上同 owner 的畫面歷史刷新。已真正保留的原件若後續 Files 作業失敗，仍維持 prepared 事實與外部未確認狀態，沒有把它改寫成下載失敗。

原件失敗會停止該 run。使用者再次選擇資料夾並開始後，重新建立 baseline，成功及失敗的舊 ID 都不回灌；只有後來出現的新 ID 可交付。既有取消、背景化、連線退休、clear epoch、provider drain gate 與已發布檔案邊界維持。

## 證據與待驗限制

共用固定 71 組 byte、34 組 MIME、6 組 header vectors 位於公開 contracts/jpeg-original/v1，JSON SHA-256 為 639e85317c0d5fbb72f4f5f8ebccfa696593c7c41881f43900021ccd30f1f6d1。App bundle 僅納入 JSON，external resource 使用 createIntermediateGroups: false，保留先前 project source 路徑修正。

- 本地 Python source／resource／contract integrity／CI helper tests：121 PASS；normal bridge cwd Ruff：PASS；git diff --check：PASS。這些只驗 fixture identity 與來源 wiring，沒有執行 Swift。
- XCTest 來源直接呼叫 production structure、MIME、closed-file validator 與 ImageIO。正例涵 RGB、灰階、progressive、optimized、CMYK、SOF1、EXIF orientation、restart、opaque metadata 與 1-pixel；負例涵 framing、表格／引用／scan／restart 與資源界限。
- 四個 premature entropy + EOI 向量可以通過結構 framing；XCTest 要求真正 ImageIO 拒絕，沒有 skip 或 expected failure。ImageIO 可能容錯修補，因此四個 native rejection **尚未驗證**，不能報為通過。若 Apple runner 接受其中任何一個，這是未解決的驗收失敗，必須修正或重新議定能力範圍，不能換掉負例或改稱完全驗真。
- 整合 XCTest 經真正 CameraAppState／CameraSession／CCAPI client，驗先成功交付、後壞 bytes／衝突 response MIME、failed history、舊 Files bytes 保留、暫存清理、明確 re-enable、兩次新 baseline 排除舊 ID、最後新 ID 可交付；相機請求始終單次原件 GET，沒有 shutter resend。
- 前版四 byte SOI/EOI 正例已改為固定真 JPEG；wire-only transport framing 測試仍可使用四 byte synthetic body，並不呼叫 JPEG validator，也不聲稱是可解碼影像。
- Native XCTest／Xcode build／iPhone／camera／Files provider／雲端同步：NOT_RUN。

## Release Assessment

本批修正既有自動交付會接受未驗原件的問題，單獨影響為 patch；不更動既有整批版本或發布狀態。待 native 編譯與上述 decoder 負例、必要 exact-head CI、原有安全／驗收 gate。物理相機與 Files provider 證據仍須分開記錄。

Primary references: [JPEG T.81](https://www.w3.org/Graphics/JPEG/itu-t81.pdf)、[ImageIO source status](https://developer.apple.com/documentation/imageio/cgimagesourcegetstatus(_:))、[ImageIO original thumbnail generation](https://developer.apple.com/documentation/imageio/kcgimagesourcecreatethumbnailfromimagealways)。
