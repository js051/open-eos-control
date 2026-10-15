# Android 自動原件 JPEG 驗證與失敗恢復

狀態：本地 implemented，原生驗收尚未執行。本記錄不代表 PR ready。

## 已實作

- 僅前景自動 JPEG 匯入；維持兩次完整 baseline、精確最新中繼資料、大小穩定、舊 ID 排除、owner／取消及單次原件 GET。手動 JPEG／RAW 下載原行為不變。
- 單次自動原件回應必須恰為 HTTP 200，且不能含 Content-Range（即使空值）；網路攔截器與返回回應兩層檢查，禁止把 206／其他 2xx 當完整原件交付。
- 完整寫入並關閉自己的 MediaStore IS_PENDING 原件後，比對原寫入與本地讀回的 byte count／SHA-256，再驗有界 JPEG 結構與真實 ImageDecoder 軟體解碼。原件不重新編碼，不增加相機 GET。
- 固定支援界線：64 MiB、64,000,000 pixels、單軸 16,384、4,096 markers、256 scans、8-bit SOF0／1／2。MIME 最多 4,096 ASCII bytes，使用線性游標解析；4096-byte 邊界測試曾抓到 regex stack overflow，已改為無遞迴解析。
- 結構解析與原生解碼分開；ImageDecoder 禁止接受 partial image，sample target 最大軸 1,024、硬上限 2,048。EXIF 轉向可交換顯示軸。取消在原生呼叫前後檢查，不能宣稱中途立即中斷 opaque decoder。
- 只有驗證成功才清除 IS_PENDING、登記已保存原件與完成交付。失敗只清除 owned pending，保留先前已完成原件和事實性的失敗紀錄。重新啟用是明確操作，建立新 baseline，不自動重抓舊 ID 或重送快門。
- Digest 證明本地位元連續性，不是相機提供的密碼學身分；有限語料與解碼接受不代表能辨識所有可能損毀。

## 已執行的本地證據

- 純 Kotlin production parser／MIME／HTTP envelope policy：16 項 JUnit 通過。
- 共用固定向量：111／111（71 byte、34 MIME、6 header）通過；JSON SHA-256 `639e85317c0d5fbb72f4f5f8ebccfa696593c7c41881f43900021ccd30f1f6d1`。
- 11 檔 Kotlin／Kotlin-script PSI 語法通過；不是 Android 型別檢查或 native compile。
- Python 來源／CI／契約檢查原 87 項通過；併入獨立 native fixture 修正的來源守門後 89 項通過，保留一個既有 warning。Bridge 設定下 Ruff 通過。
- 原生測試已補固定真 JPEG、截斷 entropy、錯格式、回復流程、保留既有成功原件及清除 pending 等案例。大檔取消 fixture 使用合法 COM segments，保留原測試資料量。
- 已補 HTTP 206／201／200+range／空 range 及回應攔截器變更案例，要求僅一次 GET、零輸出與零完成紀錄。這些 MockWebServer 測試需完整 Gradle 執行，本地 NOT_RUN。

## 待驗

Android Gradle／完整編譯、ImageDecoder 實際有效／無效語料判斷、MediaStore publication／cleanup、裝置 UI 及實體 EOS 相機均 NOT_RUN。尤其四個有合法 SOI／EOI 但 entropy 不完整的樣本，必須由實際原生測試確認拒絕，純 Kotlin 結構通過無法替代。

## Release Assessment

已核實 baseline：[v0.12.0 Development Preview](https://github.com/js051/open-eos-control/releases/tag/v0.12.0)，2026-10-15 03:54:34 UTC。建議 impact 為 patch，修補既有自動交付可靠性；版本未改。上述原生缺口尚未解除，不宣稱 PR ready 或實機可靠性驗收。
