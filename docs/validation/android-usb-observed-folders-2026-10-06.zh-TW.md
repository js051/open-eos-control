# Android USB 已觀察資料夾與媒體讀取隔離

此批接續產品矩陣 P2「找到並挑出要交付的素材」，基底為已驗收 main `7e4e7cc869e745c3a6e47501f2990714112a8ac7`。既有資料夾 UI 已支援篩選交集、隱藏選取與部分載入；本批只補 USB 後端可提供的來源資料，以及同一路徑的取消／讀回隔離。仍在本地驗收，沒有新 PR 或發布。

## 使用者流程與界線

USB 連線後讀取素材，資料夾選單使用本次列檔已取得的 ObjectInfo 父鏈。使用者可接續既有日期、評分與類型條件，預覽或保存原檔；不增加補父節點查詢、GetStorageInfo、卡槽推測、拍攝命令或資料夾 mutation。

- 來源必須有本輪實際列出的 StorageID，且父鏈完整、同 storage、沒有循環。沒有讀到父節點、limit 截去父節點或不支援的 association 仍是未知，不丟掉素材。
- 顯示 StorageID 十六進位與已讀資料夾名稱，區分來源而不宣稱 SD／CF 卡槽。物件識別沿用既有 PTP handle；資料夾識別包含 storage 與父 handle，不以名稱相同合併來源。
- 根、一般目錄與特殊 association 分開。含控制字元、歧義路徑分隔符、空名稱或超過既有 metadata 長度限制的父鏈不產生顯示資料。
- 每一輪 listing 建立自己的資料；progress 中尚未知的父鏈可以在同輪 final 變成已知，已交付的 item 不會被可變 cache 暗中改寫。
- 取消後保留已交付批次的可信來源；同 session 的預覽／info 讀回不得憑空遺失它。新的 listing 不借用舊輪未再次觀察的父鏈，舊輪也不能覆寫新輪共享 cache。
- close／新 session 的相同數字 handle 不等於相同素材。PTP 卡片素材的讀回只使用相符 session／generation 的觀察，不信任 caller 自行填入的 folder。

## 公開契約依據

Android 的 [MtpObjectInfo.getParent](https://developer.android.com/reference/android/mtp/MtpObjectInfo#getParent()) 把 storage 根目錄的 dataset parent 定義為 0。這與 [AOSP MtpClient 的 GetObjectHandles 根目錄查詢選擇值](https://android.googlesource.com/platform/frameworks/base/+/8c7d8c3ccb37edff424ca01c6474cbed2154d954/media/java/android/mtp/MtpClient.java) 不同；不能把命令參數的 `0xFFFFFFFF` 當成已確認的 ObjectInfo 根父節點。

[AOSP MtpConstants](https://android.googlesource.com/platform/frameworks/base/+/84e380e/media/java/android/mtp/MtpConstants.java) 將 GenericFolder `0x0001` 用於檔案系統目錄。本批僅辨認這個子集，沒有推測 Canon 私有 association 或實體卡槽。

## 已取得的基準證據

2026-10-06 第一個執行在 wrapper 工作目錄前置檢查失敗，未啟動 Gradle。其後第一次編譯因獨立 fixture 誤用另一測試檔的 private helper 而失敗，沒有功能案例執行；這兩個結果不計為產品反例。

修正 fixture 後，以基底 USB backend 執行新 wire class，9 項實際執行、9 項失敗。該次編譯成功，前後 source manifest 相同；新增 resolver 尚未接到 backend。已核實：

- 正向完整父鏈案例先確認包含 close 在內的原有 9 條 PTP command，接著才因期望可觀察的兩個資料夾、實得兩個 null 而失敗。沒有以增加查詢來填補缺口。
- 在 ObjectInfo 回應階段取消後，backend 仍送出新的 listing progress callback；測試明確捕捉這個結果。這不等於已證明 UI 必定顯示「完成」。
- 其餘資料夾、partial、重疊 listing 與重連反例保留各自結果，不把所有失敗概括成同一根因。

另以同一 accepted backend 單獨執行 rating 跨 session 反例，37 秒、1 項執行／1 項失敗：舊 `GET_OBJECT_PROP_DESC` 回應等待時，close 先清掉 backend session 狀態，再等待舊 PtpSession 的 shutdown mutex，因此新 transport 可建立另一 session。新 session 只列出 handle77，舊 helper 續行後卻對它送出 `GET_OBJECT_PROP_VALUE(0x9803, handle42, Rating0xDC8A)`。wire 斷言確認其餘命令與 close 一致，唯獨多了這筆舊 handle 讀取；測試未在 production 插入 yield 或使用 sleep。候選以原 session/context 執行並在 await 後檢查，修正效果仍須下列 green 驗收。基準測試 terminal 與 manifest 核對後，才復原完整候選並比對 hash。

## 第一輪候選定向驗證

2026-10-06 01:43:26–01:44:48 UTC，Gradle terminal exit0、BUILD SUCCESSFUL，原始 XML 共 **126/126、0 failure/error/skip**：新 wire12、resolver 6，以及既有 UsbPtpCameraBackend82、PtpProtocol15、PtpProperties8、AndroidUsbPtpTransport3。220個Android來源檔（包含新增檔）manifest 前後相同；backend SHA-256為 `a984cf1f11e3e92ceb5e5e179db62314cea0b2c369c63d83f5c8e37e47eb4f36`。這是定向驗收，尚未代替完整aggregate、Lint、APK或獨立檢查。

上述同一輪已確認新session不再收到舊handle rating命令，取消不再新發布progress；已交付批次的folder在同session info／preview保留。合法排程另驗證同輪final先於preview返回時使用較完整觀察、新輪開始後的舊readback不借新資料、舊listing不得回填新輪 cache。需要原本cache-miss讀回時，仍執行既有GetObjectInfo；「不增加資料夾查詢」不代表允許沿用被隔離的舊cache來省略必要讀回。

取消fixture停在完整response已取出的邊界，只證明這個可恢復交易邊界，不宣稱任意USB封包中斷皆可原連線續用。

## 審查後的結果提交與串流驗收

獨立 source review 確認原 snapshot／cache 隔離，但指出最後一次 readback 後的成功返回與 observed feature 標記尚未受保護。另有既有 stream closure 動態取得當下 session 的路徑。先在第一輪候選新增三個 JUnit 反例，35秒、3項實際執行／3項失敗；兩組各自跑完三條操作後才集中判定，沒有讓首項失敗遮蔽後項：

- metadata／protection／rating 舊回覆可正常成功；後兩者也將 feature 加入新 session。
- thumbnail／preview／download 舊回覆可正常成功並加入相應 feature；每條的命令與 bytes 斷言先通過。
- 舊 stream source 在 close／新 session 後讀取 range，會額外向新 session 送 `GET_PARTIAL_OBJECT(0x101B, handle42, offset0, length5)`；新 session 實際只列 handle77。

修正以 session-only 的結果提交邊界，在與 close invalidation 相同的鎖內驗證 session、形成回傳值並標記 feature。相同 session 的新 listing generation 不會取消合法讀取；generation 另管 cache 發布權限。Stream 固定原 ptp/context，在 range 讀取前後驗證，不借用新連線。Close 的原 cleanup 命令順序不變，末段清理不會清除 replacement 的 feature 集合。

修正後的第二輪定向為 **129/129、0 failure/error/skip**：wire 15、resolver 6、既有USB/PTP 108。六條晚回覆操作的 stdout 均確認 `cancelled=true`、`staleFeatures=[]`；舊 stream 不再向 replacement 多送命令。原 partial、generation、來源與 bytes 斷言仍通過；220檔 Android manifest 前後相同，backend SHA-256為 `a8ea562a8d79b733dfe616efce37f9357b6910b55cf8a2735a67ce827c24fa84`。獨立窄範圍覆核沒有本輪 must-fix；它是 source review，測試證據另由上述實際執行提供。

### 生命週期證據的適用範圍

`CameraRepository.connect` 每次透過 `CameraBackendFactory.create` 建立新的 USB backend。上述同一 backend 的 close→initialize 反例驗證 backend 自身 lifecycle 契約，**不能宣稱已重現 App 一般 reconnect 故障**。也不代表所有 backend 共享狀態皆已隔離：審查另指出 `refreshStorageSnapshot` 的舊結果仍缺少對 storageSnapshot／storageError 的 session 發布限制。本批 resolver 直接使用該輪 storageIds，不依賴此共享snapshot；這個較廣既有缺口保留未修、未宣稱通過。

## 最終來源完整 JVM 驗收

2026-10-06 同一 backend SHA-256 `a8ea562a8d79b733dfe616efce37f9357b6910b55cf8a2735a67ce827c24fa84` 執行不帶 `--tests` filter 的 `:app:testDebugUnitTest`，4 分 32 秒、terminal exit0。原始 XML 為 **72 suites／814 tests、0 failure/error/skip**；220 檔 Android source manifest 前後相同。上述 129 項定向包含在這 814 項之內，不能再相加；完整結果不代替裝置、Lint 或 APK gate。

同來源於 02:33 UTC 啟動一次 `:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`。執行識別失效而未取得 terminal；log 最後為 `lintAnalyzeDebug`，沒有 Lint XML 或兩個 APK，亦沒有 exit receipt。這輪結果保持**未確證**，不能把前面的編譯成功算成 Lint／APK 通過，也不能沿用舊 artifact。後續精確 head CI 若通過，只能證明該 workflow 實際執行的 gate；既有 CI 不含 Android Lint，需另有同來源 Lint 證據。

## 驗收尚待完成

- 最終同來源定向、完整 JVM 與獨立檢查已完成；後續若修改程式，須重新驗證受影響案例。
- 同來源 Lint、App／test APK 與機密掃描；若後續提交，另須該精確 head 的適用 CI。

舊雲端曾有未發布 USB 候選，但其來源在環境重置時遺失；本批使用新 source manifest 與新測試結果，不沿用其通過數。上述合成 PTP transport 驗證 production backend／session 路徑，並非 Android USB driver、實體 EOS、大卡效能或實機相容性證據。

## Release Assessment

- Latest release baseline：`v0.11.0` Development Preview。
- Proposed impact：`minor`，讓 USB 已載入素材可使用既有資料夾挑片旅程；取消／session 隔離修正屬同流程可靠性改善。
- Unresolved blockers：定向129項、完整814項 JVM 與獨立覆核已完成，仍待 Lint／APK／機密掃描及精確 PR 驗收；沒有 main acceptance 或版本 candidate，廣義 storage 狀態隔離限制仍保留。
- Physical-device status：pending。版本未修改，沒有新增相機或手機支援宣稱。
