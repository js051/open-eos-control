# Bridge 精確相機與媒體暫存退場

基準：8130d0995ed86eead34c8f418ef6fd93865170ca。這批修正既有 provider／媒體流程，不新增真 EDSDK binding 或相機功能。

## EDSDK 精確選取

真 HTTP API＋合成 provider 的兩個基準反例：要求相機 A、provider 回傳另一個合法 EDSDK descriptor B，auto／explicit edsdk 都回 201。現在在既有驗證與 cleanup 區塊加入精確 ID 比對，回 502／INVALID_PROVIDER_RESPONSE，嘗試關閉被拒 session，不登記為成功。沒有指定 ID 時仍允許 provider 選預設相機。

12 個定向測試已執行通過，包含既有選取、跨 engine exclusion、預設相機及 close 例外不洩漏。close 仍是既有 best-effort 行為，失敗不等於實際 USB 已釋放；這批沒有新增隔離或 Canon SDK／真機驗收。

## 影片 staging 所有權

兩個真 API 競態已在基準重現：完成暫存後撤銷 ticket，以及 grant 檢查後／cache.put 前撤銷。兩者均可留下無主暫存。現在直到 cache 發布與再次 ticket 檢查成功才交出 staging 所有權；失敗移除 cache 與未交接的路徑，不重送來源下載。

同一 ownership 邊界還覆蓋 iterator.close 失敗、iterator 建立失敗與暫存配置失敗。完整 staging 後 close 失敗須清檔並保留該錯誤；已經失敗的長度／讀取錯誤不能被後續 close 例外覆蓋。使用 fdopen 把 mkstemp descriptor 直接交給 writer，未交接 descriptor 在 finally 關閉，避免 close 後重新開路徑的空隙。

- 原兩個 API 競態與 completed-stage close failure：三個紅例，窄修後 16 個相關測試通過。
- 整合審查追加 iterator 建立、primary length error＋secondary close error、allocation failure 三個紅例，修後通過；另測 wrapper 建立失敗的 descriptor／iterator cleanup。
- helper fake 只驗 production staging 的例外生命週期，不代表現有相機 generator 或 Windows OS 實際重現全部故障。
- OS unlink／close 失敗仍為 best-effort，沒有把檔案刪除或硬體停止當成無條件保證。

## 其他本批契約

[gphoto upload inventory](gphoto-upload-inventory-2026-10-18.md) 保留原完整反例與來源範圍。完整整合執行結果在本批交付前另記，不將來源審查、各批測試加總當成同一次完整驗收。

Release Assessment：patch，若未來納入 Development Preview 為向後相容修正。本批不改版本、不合 main、不發布；真相機、SDK ABI、Windows／mobile native 仍未驗。

## 整合執行結果（03:45 UTC）

同一整合來源一次執行 EDSDK、API、staging、media streaming、gphoto2、upload inventory、exact-size receipt、media upload、upload outcomes、retirement 共十個 suites：210 passed，沒有 failure；既有 Starlette/httpx deprecation warning 保留。全部變更 Python 檔 Ruff 與 diff-check 通過。三個 production 檔 SHA 均與獨立審查 freeze 一致。

[精確 upload 大小收據](gphoto-upload-size-receipt-2026-10-18.zh-TW.md) 另有官方 pinned CLI 證據：list-files 取整 KB 不用作精確 bytes，使用一次 exact-target show-info；不增加 upload replay、原檔下載或刪除。
