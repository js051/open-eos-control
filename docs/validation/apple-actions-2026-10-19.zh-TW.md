# Apple Actions 驗證紀錄

此驗證只補充 Apple 平台編譯及合成測試；不代表 `ci-complete`、真相機驗收或發布完成。

## 首輪

- Source：`18aee283ea6352688da45a0a3579e1f2c54f6b24`
- Run：https://github.com/js051/open-eos-control/actions/runs/39184360780
- `secret-scan` 的完整歷史掃描、版本、驗收工具、CI/release helpers、Camera Import contract 及 actionlint 均通過。
- macOS 15.7.9 / Apple Swift 6.1.2：OpenEOSCore production module 編譯完成，測試 module 編譯失敗，尚未執行測試。
- 唯一根因：`MagnificationPeer` 未實作 `CameraHTTPTransport.download(_:)`。補上明確拋出 `URLError(.unsupportedURL)` 的 fake method；此 fixture 不支援下載，不新增 production fallback，也不改原放大／座標測試斷言。
- 失敗證據：https://github.com/js051/open-eos-control/actions/runs/39184360780/job/117843609675

- iOS App 的 unsigned Simulator build 與 bundled resources 檢查通過，fake camera 與 iPhone Simulator 啟動成功；App/UI 測試 target 編譯失敗，測試尚未執行。
- 兩個測試 fixture 的陣列型別推導為 `[Any]`：RTP packet header 與 LUT RGBA bytes。補明確 `[UInt8]` 型別，不改任何 fixture byte 或測試斷言。
- App 失敗證據：https://github.com/js051/open-eos-control/actions/runs/39184360780/job/117843609688

修正後結果待後續 Actions 執行，不以本地源碼檢查當作 Apple 測試通過。

## 第二輪核心執行

- Source：`64eb0680aaa15b29fdf5edc168b207d79c452fce`
- Run：https://github.com/js051/open-eos-control/actions/runs/39185127429
- Core 的測試 module 已成功編譯並實際執行 665 個案例；11 個案例共 19 個失敗斷言（7 個 unexpected error）。不是 19 個失敗案例。
- 三個 Bridge session replacement 案例得到 `sessionChanged`，另行核對嚴格 session ownership 與 fixture 的契約。
- 八個 URLSession 案例涵蓋 capture configuration、maintenance、recording、shooting settings 的 GET redirect／取消／deadline：Apple URLSession 對 GET streamed upload task 回傳 `NSURLErrorDomain -1103`，peer 未收到請求。
- 修正 GET 使用同一個 strict session/delegate 的 data task；寫入仍採單次 body streamed upload task。GET 非空 body 於派送前拒絕；新增四個負例。未放寬 redirect/auth、回應大小、完整 EOF、timeout 或取消規則。
- 原失敗 log：https://github.com/js051/open-eos-control/actions/runs/39185127429/job/117845912674
- 此修正目前只通過 Linux Swift 語法解析，仍需後續 Apple 執行證據。
- Bridge 三案確認是舊成功-close fixture 回傳 `200` 加 JSON，而既有契約要求 `204` 空 body；只修各 exact DELETE session endpoint，並加上 close 確認斷言。production 的未確認退場禁止重開防護不變。
- App 第二輪仍止於 test target 編譯：`ConnectionCancellationTests` 五處區域變數從 implicitly-unwrapped optional 屬性推導成 optional。改為 `try XCTUnwrap` 明確驗證 fixture 已建立；未變更流程與原斷言。
- App 失敗 log：https://github.com/js051/open-eos-control/actions/runs/39185127429/job/117845912595
