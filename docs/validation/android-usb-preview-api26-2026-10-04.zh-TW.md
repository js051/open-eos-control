# Android USB host 預覽的最低版本相容性

## 已確認問題

來源 head `c6779210b2c34ff2dd7fa1922799d5a1c96d2c5d` 的 `AndroidUsbHostCaptureStore.preview` 無條件呼叫 `InputStream.readNBytes(int)`。App 的 `minSdk` 為26，沒有 core-library desugaring 或版本 guard；Java17 編譯目標不會讓舊 Android 平台自動具備該方法。[Android 官方 API 文件](https://developer.android.com/reference/java/io/InputStream#readNBytes(int))標示此方法從 API33 才提供。

以 Lint8.8.2 分析原來源可重現 `NewApi` error。現有 API34／36 測試無法直接發現 API26–32 缺少此方法，因此不能用其綠燈否定此相容性錯誤。

## 最小修正

- 保留既有檔案大小門檻及最多上限＋1 byte 的 overflow sentinel，仍拒絕超限或不完整 JPEG／PNG。
- 改用平台 API1 就有的 `InputStream.read(byte[], offset, length)`／`read()`；編譯後 bytecode 亦只引用這兩種讀取。
- 從最多64KiB開始配置，依需要有界成長，每次實際讀取最多64KiB；檔案在檢查後成長也不會無界讀完整檔案。
- bulk read 回傳零時以單byte取得進展或EOF，不忙迴圈。每次讀取前後檢查coroutine取消。
- 由呼叫端 `use` 持有並關閉stream；移除多餘的buffer wrapper以避免超出讀取上限的read-ahead。沒有改相機指令或下載／編碼政策。

## 驗證

15個JVM案例涵蓋空來源、短讀、內嵌零byte、精確上限、上限＋1、檔案成長、buffer成長、零進度／EOF、零／負limit、IO錯誤、caller關閉責任，以及讀取前／中／最後一段的取消。

修前新版Lint報1個NewApi error；修後新版Lint0 errors、58 warnings，沒有registry載入失敗；instrumentation已編譯。最終整批aggregate與遠端精確head結果另記於PR，不能用此focused結果代替。

沒有執行API26實體手機／模擬器或實體相機驗證；證據層級為官方API契約、Lint、編譯產物方法引用與JVM故障測試。
