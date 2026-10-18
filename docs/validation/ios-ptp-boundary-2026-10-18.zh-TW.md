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

## 下一步與狀態

先在具備 Swift 的合法工具鏈執行這 29 項，再於 Xcode 建立／驗證 native adapter。Apple completion buffers、PTP framing／transaction ownership、callback executor、權限順序與實機 close 行為仍需核實；不可把 Android USB 命令或 cancellation 假設直接移植。

原 #129 仍未提供可用的 iOS USB/PTP 功能；本批只完成隔離介面／狀態機源碼與待執行測試。公共 API 研究見 [公開契約核對](public-provider-contracts-2026-10-18.zh-TW.md)。

Release Assessment：none。這是未啟用的內部基礎，不變更版本，不建立發布，沒有 native/device acceptance。
