# #129 production PTP coordinator

原切片只有 port 與 reducer，真正的 effect pump 位於測試 fake。現在新增 Foundation／Dispatch production ImageCapturePTPCoordinator，將輸入序列化並在每個 effect 執行前重查原 admission；原 29 案不再以 fake 自行派送效果。

- 私有 serial executor；跨 thread callback 只入 inbox，立即返回，不等待 executor，沒有鎖跨 port.perform。
- callback 或 reentrant send 先影響下一個 effect 的 admission；snapshot 可在 callback 中取得已歸約狀態，不巢狀呼叫 perform。
- 已綁定 receive 的 port 拒絕新 owner，不覆蓋舊清理回呼。建構與 binding 要先於跨 thread 使用；不宣稱任意同時建構安全。
- session／request／deadline 身分沿原 reducer；queued read、已派送未知結果及晚 open 清理仍分開。
- port.perform 必須非阻塞；不得等待另一 thread 的同步 send／snapshot。coordinator 必須由呼叫者持有到 discovery／active session／cleanup 義務結束；deinit 不代表 native close 或取消完成。

## 已執行

2026-10-18 15:13:49 UTC，以原有官方 Swift 6.4.1、EOS 隔離 module/cache/build 目錄執行原 Package 與完整三個 production 檔、兩個 XCTest 檔：37/37 通過。原 29 個 test 本體／斷言不變；新增 8 案涵蓋 binding、跨 thread callbacks、重入取消與 snapshot、queued read、舊 deadline／late open 及 concurrent input。來源 SHA 前後相同。

首輪唯讀預設 module cache 錯誤、第二輪新測試 Request 欄位拼寫編譯錯誤均保留；已修 harness 可寫 cache 與新測試欄位，沒有弱化斷言。另 6 項 PTP source scope guards、4 項 project source path guards 通過；這 10 項不算 XCTest。

[命令、來源 hash、完整失敗與成功輸出](evidence/ios-ptp-coordinator-2026-10-18/)。Linux 切片不等於完整 OpenEOSCore／Apple ImageCaptureCore 編譯；未新增原生 adapter、設備存取、授權提示、產品入口或 PTP wire codec。#129 尚未提供完整可用的 iOS USB 功能。

Release Assessment：none。無版本、tag 或 release。
