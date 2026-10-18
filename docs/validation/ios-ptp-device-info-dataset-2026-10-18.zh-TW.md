# #129 Swift DeviceInfo dataset 與 Android 對照

新增 Foundation-only PTPDeviceInfo／PTPDeviceInfoDecoder，接受已分離的 dataset，解出 14 欄及 operation membership。未新增 command encoder、container／response／transaction 處理，也未接入 ApplePort；native read 仍明確 unsupportedWireContract。

## 相容契約

依既有 Android PtpProtocol.kt 的 PtpDatasets.deviceInfo 與 PtpDataReader：little-endian 無號整數；五組 UInt16 capability 集合保留未知代碼、去重；末四個字串可整欄省略，已開始而截斷則拒絕。counted string 丟棄最後一個 code unit，再 trim 尾 NUL；不額外驗證 terminator，也接受尾隨 bytes。這些是既有 Android 相容行為，不宣稱嚴格 PTP 規格驗證。

Swift 預設 UTF16 修復與 JVM 並非完全相同。此 decoder 明確沿用已測 Java UTF_16LE malformed-surrogate 消耗規則；測試以 UTF16 hex／scalar 值核對，避免 Swift canonical-equivalence 的字串相等掩蓋差異。

輸入在複製前限制 16 MiB，對齊 Android transport metadata 預算；Android dataset 函式本身原無此限制。array count 先驗 Int32／剩餘長度，之後才配置；Set 容量最多 65,536 個 UInt16 值。支援非零 startIndex 的 Data slice。

## 已執行驗證

- 既存、來源 hash 相符的完整 Android production PtpDatasets classes：13 組新 exact-byte fixtures，逐欄記錄真輸出；不是抄預期值或重跑既綠套件。
- Swift：原 50 項保持，新增 14 項，共 64/64 PASS。13 組同 byte oracle 全部成功欄位精確一致，拒絕 case 亦一致；另涵蓋每個截斷 prefix、可省尾欄、巨大 count、未知／重複 codes、254-code-unit 字串、16MiB 邊界、Data slice、BOM、分解 Unicode 與 surrogate pair。
- 來源 hash 前後一致。這不是 Apple SDK 編譯、實體 USB、Apple completion framing 或完整三平台驗收。

[測試、來源 hash、Android oracle 與重現資料](evidence/ios-ptp-device-info-dataset-2026-10-18/)。

Release Assessment：none。無版本、tag 或 release。
