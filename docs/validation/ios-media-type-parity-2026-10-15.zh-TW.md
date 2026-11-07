# iOS 素材類型與安全 JPEG 資格：來源實作與待驗收紀錄

日期：2026-10-15。狀態：本地 `implemented` 候選，尚非 `PR ready`。

## 範圍與固定契約

- `OpenEOSCore/CameraMediaType.swift` 提供唯一的分類器，明確區分 photo、video、audio、other、unknown、conflict。
- 相簿篩選、拍照／錄影回看、縮圖、預覽、原生播放入口及串流回應共用分類結果。unknown、conflict、audio、other 不進入影像／影片解碼路徑；「全部」與最近素材仍保留相機順序。
- 保留既有 photo/video poster 縮圖路徑、影像 payload 驗證、CCAPI JPEG/CR3 display 路徑限制、`previewAvailable` 與能力檢查。分類本身不新增傳輸或解碼支援。
- 固定契約：`contracts/media-type/v1/media-type-vectors.json`，22,867 bytes；SHA-256 `d24efab5bb73d5edc1d222395322f4295c4c31a3ae180d8b3d9293ce4e4b7d14`。71 分類、15 fresh metadata、4 順序選擇、19 MIME alias 向量。
- MIME aliases 共用 raw resolver，保留空字串、空白及 null 語義；不同宣告、非字串／非 null 欄位皆拒絕。ASCII 大小寫處理不接受 Unicode 折疊；MIME 任一位置的 ISO control，包括參數內的控制字元，皆拒絕。

## 自動交付門檻與 fresh metadata

- 自動 JPEG 必須是單一 literal 檔名（無 `/` 或 `\`、最後一個點之前非空）、literal `kind=image`、`.jpg/.jpeg` 副檔名、無衝突 photo 分類，以及缺少／空字串 MIME 或正規化 `image/jpeg`。`image/jpg`、空白 MIME、generic MIME、RAW 或大小寫／空白 kind alias 不取得自動交付資格。
- 嚴格前景 CCAPI info 的缺省名稱／kind 綁定 canonical request path；缺少 MIME 回傳 nil，不沿用快取或猜測 MIME。回應宣告的 ID、名稱、kind 及 MIME 不再被舊 listing 值蓋掉。
- ID／名稱必須維持原始 UTF-8 bytes。正數 size 與嚴格類型通過後，仍須兩次一致的 size 觀察及原有間隔，才可取得一次原始檔。
- `freshSizeUnavailable` 是有期限的等待：清除既有 size 穩定證據，沿原有一秒觀察間隔等待；不重設 pending 的 30 秒期限。恢復後必須重新完成兩次正數觀察。真正請求失敗、身分改變或正數 metadata 衝突會停止。
- 保留完整雙 baseline、所有已知 ID 排除、queue/result 上限、前景／session owner、取消與歷史記帳。Files 從已保留且有 lease 的原始副本交付；保留後不新增相機 info GET，不重送 shutter／REC／AF。

## 驗證結果與缺口

已執行：

- Python `scripts/validation/tests`：49 tests 通過，涵蓋既有日期／回看及新增來源、fixture、資源 mapping guards。
- 合併執行 `scripts/ci/tests`、`scripts/validation/tests`、`scripts/contracts/tests`：pytest 101 passed；1 個既有 `test_export_ios_ui_evidence.py::test_tree` 回傳 dict 的 pytest warning。此為 Python／來源檢查數，非 XCTest 執行數。
- 最後更新單一檔名／dotfile 向量、alias 與延遲縮圖／預覽守門後，重跑受影響素材來源檢查：14 tests 通過。
- 在 bridge 目錄執行 `ruff check --no-cache . ../scripts/validation`：通過。
- `git diff --check`：通過。

已新增但 **NOT_RUN**：

- Swift 直接消費全部固定向量，呼叫正式 classifier、gallery、review、foreground plan 及 raw alias resolver。
- Core adapter／stream tests：錯誤類型零 GET／零 playback allocation、fresh 欄位漂移、不合法 MIME aliases、串流 MIME 衝突、嚴格下載入口拒絕。
- App controller／integration tests：第二次 fresh 類型漂移時零原始檔、零保留／Files copy；unready → 重新兩次正數觀察；不延長期限的 expiry。
- SwiftPM、Xcode App/UI、實際 iPhone／相機／Files provider 驗收皆未執行。來源 guards 不能取代 Swift 編譯與原生驗收。

本批未擴大既有原始檔 payload policy：原始檔仍驗證正確 byte count 並拒絕文字，但未新增 JPEG signature／完整解碼驗證，也未新增對非文字原始回應 MIME 的完整 JPEG 白名單。不得據此宣稱已證實原始內容是可解碼 JPEG。

## Release Assessment

- 基準產品版本：0.13.0 Development Preview。
- 建議影響：`patch`，修正類型誤判與自動 JPEG 安全門檻，不新增傳輸能力。
- 未解決門檻：此候選尚未通過原生編譯、App/UI 與 exact-SHA CI；無新增實機證據。
- 本紀錄不授權或表示已推送、合併、發版、安裝、修改 CI／安全設定。
