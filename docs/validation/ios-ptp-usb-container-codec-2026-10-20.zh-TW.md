# iOS #129：標準 USB/PTP container codec（獨立基礎層）

## 範圍與限制

新增 Foundation-only `PTPUSBContainerCodec`，不改變 `ImageCapturePTPApplePort`、provider 選擇、原生派送或連線流程。Apple adapter 的 `unsupportedWireContract` 保持原狀。這不是 Apple `requestSendPTPCommand` 或 completion buffers 的 wire-contract 實作，也不是 iOS USB/PTP 功能可用證明。

參考來源為本 repo 的 Android `data/PtpProtocol.kt` 中 `PtpCodec` 與 `PtpProtocolTest.openSessionCommandMatchesUsbPtpReferencePacket`。OpenSession fixture 直接沿用既有 Android golden bytes；其他 literal fixtures 是依照同一 framing contract 編寫，不能稱為本輪重新執行 Android oracle 的結果。

## 實作契約

- Command：12-byte little-endian header，UInt16 operation code、UInt32 transaction ID、0–5 個 UInt32 parameters。型別避免數值窄化；不自行分配 transaction ID，不將 correlation UUID 當成 wire ID。
- Response：只接受一個完整 type=3 container，長度精確相等，payload 至多 20 bytes 且必須 4-byte 對齊；transaction ID 必須逐位相等。保留原始 response code（包含非 OK 與未知／vendor code），不將成功解碼等同相機操作成功。
- Data：只接受 type=2、operation code 與 transaction ID 均相等的完整 container。明確剝離 12-byte header 後才回傳 dataset。沿用 `PTPDeviceInfoDecoder.maximumDatasetBytes` 的 16 MiB payload 上限。這是 bounded metadata 解碼器，不是大型照片／影片串流。
- 先檢查總大小，再只複製固定 12-byte header；避免依不可信 declared length 配置記憶體。支援 nonzero-index `Data` slice，不使用未對齊記憶體載入。截斷、額外 bytes、串接 containers、錯誤 type／transaction／operation 均拒絕。
- Dataset 內容解碼與 container framing 分離；即使取得 dataset，仍需另行驗證 session ownership 與 response status。既有 DeviceInfo decoder 的相容性語義未變更。

## 自動化測試

新增 `PTPUSBContainerCodecTests`，涵蓋 Android OpenSession golden、GetDeviceInfo golden、五參數與 unsigned 邊界、response golden／非 OK code、所有短 header、無效與不相符 length、串接 containers、錯誤 types、0–24 byte response payload、transaction 每個 byte 的差異、operation mismatch、data golden、dataset/container 分層、nonzero-index slice、16 MiB 邊界。

執行結果：2026-10-20 11:46:53–11:47:04 UTC，既有 Swift 6.4.1 Linux toolchain 的 scoped harness 首次執行通過，81 tests / 0 failures（原 64 項保留，加上 17 項新測試）。source hashes 前後一致。沒有安裝、下載、原生相機派送、CI 或推送。

執行限制為兩核心 affinity、`--jobs 2`、120 秒 timeout；每 0.2 秒觀測自有 process group 的 RSS，超過 768 MiB 即終止。本次最高觀測 RSS 186,150,912 bytes（約 178 MiB），沒有觸發終止。這是 observational watchdog，並非 cgroup hard memory limit。raw XCTest log 使用環境當地時間；`result.json` 時間明確為 UTC。

完整原始測試紀錄、11-file source manifest、結果及資源觀測保留在獨立驗證工作目錄，不納入此次三檔 repo delta。已測來源 SHA-256：

- `PTPUSBContainerCodec.swift`：`845cfd24e2663f40ba7c9410e7ed48f9549ab685453e952786cd8aa1828bcf74`
- `PTPUSBContainerCodecTests.swift`：`5342247dfd73fa00ee6cc6d1e5c116a224dd2d56a269f7c4e834687f16b1c880`
- 未修改的 `ImageCapturePTPApplePort.swift`：`6da1bca243dd777d5636471380ad5cc61763fca0bc5ac96858fd722b94579cf0`

重現範圍：將 `ios/OpenEOSCore/Package.swift`、`Sources/OpenEOSCore` 內的 `ImageCapturePTPPort`、`ImageCapturePTPStateMachine`、`ImageCapturePTPCoordinator`、`ImageCapturePTPApplePort`、`PTPDeviceInfo`、`PTPUSBContainerCodec` 六個 Swift 檔，以及 `Tests/OpenEOSCoreTests` 內對應的 StateMachine、Coordinator、DeviceInfoDecoder、USBContainerCodec 四個測試檔，保留相對路徑複製至獨立 Swift package，使用既有 Swift 6.4.1 執行 `swift test --package-path <slice> --scratch-path <build> --cache-path <cache> --config-path <config> --security-path <security> --skip-update --disable-netrc --jobs 2`；module cache、XDG cache 亦放於獨立 harness 目錄。沒有執行與此次範圍無關的 app／package targets。`--skip-update` 只產生 deprecated option warning，不影響此無外部 dependencies 的測試。

## 驗收與發布判斷

- Apple SDK 編譯：本輪未執行；Linux Foundation 測試不能替代。
- 實機：未執行；無相機、transport、app build 的實機驗收證據。
- Native wire contract、實機 session／transaction ownership、真實錯誤回應與資料順序仍待獨立驗證。
- Release Assessment：本獨立 delta 為內部基礎層與測試，impact `none`；沒有可對使用者宣告的新 provider 能力，不單獨要求版本 bump。未 push、未 CI，不能稱為 PR ready／main accepted／preview released。
