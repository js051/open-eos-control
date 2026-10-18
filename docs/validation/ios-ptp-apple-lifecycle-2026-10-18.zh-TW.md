# #129 Apple 首次 session 隔離切片

新增 ImageCapturePTPApplePort 原碼，以 main-thread coordinator 串接公開 ImageCaptureCore discovery、授權、open/ready、事件與 close。沒有產品入口、權限提示、entitlement 配置或真設備操作。Apple 條件分支 **NOT_COMPILED / NOT_RUN**；Linux XCTest 不驗證 SDK 型別、Swift importer、actor／Sendable 診斷或原生回呼。

## 共享契約與實測

- openNotDispatched 僅供 native open 未呼叫且未安裝任何新 session 資源的拒絕；精確移除該 request，保留拒絕原因，不假造 close 或 release。
- sessionInvalidated 以精確 session 撤銷能力／存取，不把失效當成實體已關閉，不改全域授權或新 owner。
- 已 observed ready 與「未送出」矛盾時保留清理責任，包括 cancel／replace 後才到的 ready；不復活舊 phase 或送 read。
- 原 40 項保持。第一批新增 8 案：48 案中 5 個新測試／12 assertions 先失敗，修後 48/48。窄審補取消及替換後 late-ready 的兩種順序：50 案中 2 tests／4 assertions 先失敗，修後 **50/50**。來源 hash 前後一致，原始紅／綠輸出保留。

## 原生原碼的明確限制

- 只以 retained ICCameraDevice 物件身分配發 handle，不以名稱、UUID 或 VID/PID 合併。
- process-lifetime 強保留唯一 port／owner；第二次 make 明確失敗。獨立 maximumRetainedDevices=4 是此隔離切片的資源上限，不是 reducer cleanup 容量。超額物件不保留、不宣告可用，不逐出舊責任。
- 每個 exact native object 最多一次 attempted open；包含失敗／逾時。尚無晚 ready 世代可核實契約，因此不支援同物件重開，也不支援程序內重啟 adapter。
- open／close 只以 exact-request completion 作收據；delegate 另處理 unsolicited lifecycle。PTP event 只走 handler。退場保留責任，失敗／逾時不釋放；確認 close 後才移除 delegate／以空 handler 隔離事件，未宣稱原生 callback 已排空。
- DeviceInfo 明確 unsupportedWireContract，沒有發送 PTP bytes。Apple 的 buffer framing、transaction ownership、真機回呼及實體 close 仍須驗證。

[收據與重現資料](evidence/ios-ptp-apple-lifecycle-2026-10-18/)。官方 API 來源清單保留於該證據目錄。這是 #129 的未接線原碼與已執行共享核心測試，尚不是可用 iOS USB provider。

Release Assessment：none。無版本、tag 或 release。

## 單 native session 的保守限制

官方未承諾跨 browser 的 ICCameraDevice 物件穩定性，不能據新物件判斷它必為另一實體相機。adapter 在建立任何新 session 資源前要求 sessions 為空；任何 open／close／逾時／失敗責任仍 retained 時均回 openNotDispatched(unsupportedConcurrentSession)。只有 confirmed close 的 release 才移除原 context。同步 close completion 在 release 前的重入仍可能被保守拒絕，需再次明確選擇，不自動重試。

這是 **source-only** 的單 native session 限制；同樣 NOT_COMPILED／NOT_RUN，不證明實體身分、框架釋放或跨 browser 重開安全。共享核心 64 項回歸僅核事件型別與既有拒絕／清理契約，不能驗證 Apple guard 的原生執行。
