# #129 iOS PTP 隔離介面與狀態機切片

## 本批程式

- `ImageCapturePTPPort.swift`：value-only adapter port，使用精確 opaque handle、discovery/session/request 身分，僅提供 semantic read-only DeviceInfo。沒有任意 raw packet／outData 入口。
- `ImageCapturePTPStateMachine.swift`：授權、open＋ready、單次請求 admission、事件、退場及 late callback 清理。queued 與 dispatched 分開；已派發但中斷的讀取保留 outcomeUnknown，不能當成相機取消成功。
- close timeout／失敗保留同 object quarantine；晚到 open 仍有原 session 的 close 義務。最多保留四個 active／未解決 session，滿額拒絕新 open，不丟棄舊清理義務。
- 完成 callback 的兩份 buffer 保持分開且只標 unvalidated；沒有 DeviceInfo 語義成功、真機可用或寫入操作宣稱。
- 延遲 release／stop effect 只屬於原 session／browser；假 port 的 reentrant 回呼測試覆蓋重新選取相同 handle。

此切片尚無具體 ImageCaptureCore native adapter、產品入口、Info.plist 權限、entitlement 或自動連線。既有 CCAPI／Bridge 程式未修改。

## 驗證狀態

2026-10-18 01:49 UTC，在整合工作樹執行：

- `python -m unittest discover -s scripts/validation/tests -p 'test_ios_*_source.py'`：106 passed，其中本批新增 6 個範圍守門檢查。
- `python -m unittest discover -s scripts/validation/tests -p test_ios_project_source_paths.py`：4 passed。
- `git diff --check`：passed。
- 29 個 `ImageCapturePTPStateMachineTests.swift` fake XCTest 已撰寫，**NOT_RUN**。它們使用 production reducer/admission，不複製另一份狀態機作為驗證替身。
- Swift 編譯、XCTest 執行、Xcode iOS build、ImageCaptureCore、授權 prompt、USB/PTP 與真相機：全部 **NOT_RUN**，既有雲端無 Swift/Xcode。本批沒有下載工具。

來源檢查只證明 imports、API surface、產品未啟用與測試案例存在；不證明 Swift 可以編譯或生命週期行為通過。獨立來源審查發現的 cleanup 無上限及舊 release ownership 問題已在交付前修正，但審查不替代執行。

## 2026-10-18 06:36 UTC 執行補證

來源固定為 `022b3eff0d4fbdc5631f7409031a2890632118b9`。在 Debian 13 x86_64 的隔離工具目錄使用官方 Swift 6.4.1，原封不動複製原 `Package.swift`、兩個 PTP production Swift 檔與原 `ImageCapturePTPStateMachineTests.swift` 到獨立 package。以 Git blob SHA-1 逐一核實來源，沒有 stub、翻譯狀態機或改寫測試。

- **29 XCTest executed，0 failures**，完成時間 06:36:22 UTC。這項結果取代上方歷史紀錄中的 PTP `NOT_RUN`。
- [完整 PTP 編譯與 XCTest 輸出](evidence/ios-ptp-linux-2026-10-18/ptp-swift-test-initial.txt)。輸出原時區為 UTC+8；已把工作目錄前綴替換為 `<EOS_WORKSPACE>` 並移除行尾空白，測試名稱與結果未修改。
- 完整 OpenEOSCore 91 個來源／測試／manifest 檔亦恢復並核實 blob，隨後 `swift build --build-tests --jobs 2` **失敗**：`CameraHTTPTransport.swift:771,774` 的 `NSKeyValueObservation` 不存在於 Linux。[失敗輸出](evidence/ios-ptp-linux-2026-10-18/core-build-initial.txt)。這不證明 Apple build 有同樣錯誤。
- 其他原封不動 XCTest 的依賴閉包皆到 HTTP transport：例如 RTP → CCAPIError → CameraModels → CameraRecordingLifecycle → CameraHTTPTransport；只用 test filter 無法避免編譯。沒有移除 readiness gate、替換錯誤型別或為通過 Linux 修改產品。
- Xcode iOS build、完整 Core XCTest、ImageCaptureCore adapter、權限、USB/PTP 與真相機仍 **NOT_RUN**。29 項只證明純 reducer／fake port 契約通過，不證明 iOS USB 功能完成。

### 工具來源與重現

- [官方 Debian 13 安裝頁](https://www.swift.org/install/linux/debian/13/)
- [Swift 6.4.1 archive](https://download.swift.org/swift-6.4.1-release/debian13/swift-6.4.1-RELEASE/swift-6.4.1-RELEASE-debian13.tar.gz)，1,122,730,813 bytes；SHA-256 `3273c647a4e29231e1d0962a170f4ba14eca06675591dfe384e7c22b0e476a47`。
- 同官方 URL 加 `.sig` 的簽章核實 `GOODSIG`／`VALIDSIG`；公鑰來自 [Swift 官方 6.x key](https://www.swift.org/keys/release-key-swift-6.x.asc)，fingerprint `52BB7E3DE28A71BE22EC05FFEF80A866B47A981F`。沒有新增系統信任或改 TLS。
- 解壓前檢查 2,533 entries，logical size 3,761,471,517 bytes，單一預期 root，無無效路徑；使用 Python tarfile data filter。無額外系統套件安裝。
- 在上述四個檔案組成的獨立 package 執行 `swift test --package-path <slice> --cache-path <cache> --config-path <config> --security-path <security> --scratch-path <build> --skip-update --disable-netrc --jobs 2 --filter ImageCapturePTPStateMachineTests`。原 package 沒有外部 SwiftPM 依賴。完整 Core 編譯使用另一 scratch 目錄，兩份來源都未修改。

## 下一步與狀態

29 項純核心測試已執行；下一個不同層級的門檻是在 Apple/Xcode 環境編譯完整 Core，並建立／驗證 native adapter。Apple completion buffers、PTP framing／transaction ownership、callback executor、權限順序與實機 close 行為仍需核實；不可把 Android USB 命令或 cancellation 假設直接移植。

原 #129 仍未提供可用的 iOS USB/PTP 功能；本批完成隔離介面／狀態機源碼與 29 項 Linux fake XCTest；原生 adapter 與實機驗證仍未完成。公共 API 研究見 [公開契約核對](public-provider-contracts-2026-10-18.zh-TW.md)。

Release Assessment：none。這是未啟用的內部基礎，不變更版本，不建立發布，沒有 native/device acceptance。
