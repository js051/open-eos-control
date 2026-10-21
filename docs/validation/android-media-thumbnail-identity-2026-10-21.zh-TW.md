# Android 縮圖的觀察到之媒體身分與讀取擁有權（2026-10-21）

## 範圍與反例

基線為 `153901e458cec581ba72a9cc2457ec5bfead4863`。G08 的媒體瀏覽／預覽來源檢查發現：Android 的縮圖 bitmap 與工作皆以 opaque ID 為索引；完成的手動清單直接替換 `mediaItems`，部分／事件清單僅以 ID 保留縮圖，`mediaInfo` 讀回也未使舊縮圖失效。

因此，在允許的清單／資訊讀回回傳相同 ID、但不同名稱或不同已確認視覺分類時，舊 bitmap 可以繼續出現在新項目，且快取命中會阻止新縮圖讀取。這是實際 ViewModel 呼叫鏈可達的合成反例，不是已觀測到的實機相機錯圖報告。

本批只變更 `CameraViewModel.kt`、新增 `CameraMediaThumbnailOwnershipTest.kt` 與本文件。沒有變更線路契約、相機寫入、Preview／Video 所有權、重試延遲、KVM、工作流程或必要驗收。

## 修正邊界

- 對比觀察到的 ID、名稱，以及現有分類器判定的 PHOTO／VIDEO。雙方必須都為無歧義視覺媒體，且分類一致。
- 手動清單最終頁、部分／事件清單與資訊讀回共同使不相符的縮圖／載入狀態失效。手動／部分清單原有 `preservePreview` 行為不變。
- 資訊讀回改變此身分時，只先移除該 ID 的工作擁有權再取消工作。其他讀取、一般評等／保護／封存變更與未變身分之 bitmap 快取仍保留。
- 舊 tile 不能讀取、碰觸或取消不同已觀測身分的新項目。完成回呼必須同時符合清單身分、原 generation 與確切 Job 擁有權；舊 `finally` 不能清除後繼工作的載入狀態。
- 縮圖回傳的項目也必須符合相同視覺分類；不因雙方都可視覺化，就將相片與影片視為同一來源。

這個 metadata signature **不是位元組內容身分**。相同 ID／名稱／分類仍可能對應改寫過的位元組；本批沒有內容雜湊、遠端版本號或原子儲存卡快照契約，不能宣稱解決所有檔案覆寫或所有預覽生命週期問題。大小、評等、保護等其他欄位不會自行推定成新內容版本。

## 可反駁驗證計畫

新測試直接執行正式 ViewModel 與 Repository 的 `refreshMedia`、`loadMediaInfo`、`loadMediaThumbnail`、`cancelMediaThumbnail`。測試建立明確合成的已連線狀態，只以受控 backend 取代讀取；Robolectric SDK 35 提供 bitmap 建立／解碼。沒有真相機或外部網路。事件轉換的案例直接呼叫正式 `withEventMediaItems`，不宣稱完整事件輪詢或 Compose 排程驗收。

凍結基線應有以下十個 assertion RED，編譯／環境／fixture 失敗不算 RED：

| 測試方法 | 唯一預期 assertion 標記 |
| --- | --- |
| `finalRefreshRetiresChangedNameAndDemandsReplacement` | `THUMB_FINAL_REPLACEMENT` |
| `partialRefreshRetiresChangedNameBeforeFinalPage` | `THUMB_PARTIAL_REPLACEMENT` |
| `eventRefreshRetiresSameIdChangedVisualFamily` | `THUMB_EVENT_FAMILY` |
| `infoReadbackRetiresChangedName` | `THUMB_INFO_REPLACEMENT` |
| `oldTileCannotReadReplacement` | `THUMB_STALE_ADMISSION` |
| `oldTileCannotCancelReplacementRead` | `THUMB_STALE_CANCELLATION` |
| `changedInfoRetiresTheOldPendingThumbnailOwner` | `THUMB_INFO_PENDING_OWNER` |
| `lateOldCleanupCannotClearReplacementLoading` | `THUMB_SUCCESSOR_ADMISSION` |
| `changedReturnedVisualFamilyCannotSupplyTheThumbnail` | `THUMB_RETURNED_FAMILY` |
| `unknownOrConflictingReplacementCannotRetainVisualCache` | `THUMB_NONVISUAL_REPLACEMENT` |

其餘四個新控制案例須在基線與候選均通過：相同 signature 保留兩個既有 bitmap，快取命中不產生 backend 讀取；一般資訊 metadata 保留快取／讀取；目前擁有者取消後仍可重試且不清掉其他快取；移除 ID 後舊工作不能重新發布。候選需全數通過十四個新案例。原有 `CameraUiStateTest`、`MediaLibraryTest`、`CameraMediaPreviewOwnershipTest` 與 `CameraMediaPlaybackCacheTest` 原文／assertions 不修改。

## 實際離線驗證結果

原通知的 295 秒累計視窗先執行 99.284532 秒：77 個正式資料檔編譯成功，UI／測試編譯因缺少既有純函式而失敗，尚未執行測試。補齊精確來源 closure 後，原視窗剩餘時間不足，所以沒有重設時鐘或啟動趕時間的續跑。

另行通知的 240 秒視窗實際執行 51.714499 秒：基線 UI／測試編譯成功，十四個新案例執行完畢，但出現下節記錄的十一個失敗，因額外 LRU 斷言失敗而停止。這次沒有放行候選，也沒有把額外失敗改列為預期 RED。

在保留原失敗證據、釐清新正向控制範圍後，再另行通知 150 秒最終視窗，實際執行 70.581536 秒，於 **2026-10-21 00:55:03.837992 UTC** 完成：

- 基線十四例：精確十個原定身分 assertion RED，四個未受影響控制 PASS；案例 ID／錯誤類型／標記逐一比對。
- 候選 **90／90 PASS**：`CameraUiStateTest` 15、`MediaLibraryTest` 28、雙 transport 的 `CameraMediaPreviewOwnershipTest` 28、`CameraMediaPlaybackCacheTest` 5、新縮圖測試 14。
- 候選正式檔 SHA-256：`6fb20f685cd80213028efc07aafb2ab4c370d2d895b2e521709c473e17b648eb`；新測試 SHA-256：`cbd453b69f8c171970e1f029a3e63cd1a6172b4133aed3eb65d7028da75b27b1`。
- 三次實際 gate 執行合計 **221.580566 秒**，但分屬上述三個已通知視窗，並非宣稱第一次 295 秒視窗內一次完成。原設定失敗、原十一失敗結果、未執行續跑決策與最後合格結果分開保留。
- 全批限兩個 CPU；最高取樣 process-tree RSS 為 **750,108 KiB**，未越過 768 MiB guard。這是定期取樣與超限終止，不是 kernel hard memory cap。全程保留 2 GiB 共享磁碟下限，各階段另核對 32 MiB 餘裕。

全部使用既有工具與依賴；正式來源重新編譯，續跑只重用此次成功且雜湊綁定的 data／UI jar。修訂後的完整新測試檔另編譯成優先 jar，候選 VM jar 又在其前；不使用歷史的 UI/data/test 編譯目錄。原四個相關測試檔與基線位元組一致。此為 Linux/JVM/Robolectric 的局部證據，尚不是 Android host CI、完整 APK 或裝置驗收。

## 已發現但不在本批修正的 LRU 呼叫端缺口

原新控制案例曾同時要求快取命中更新 key 迭代順序。第一次完成基線測試的實際結果為十四個執行、十一個失敗：十個規劃中的身分 assertion，加上此項未預期失敗。預期順序 `[synthetic-other, synthetic-reused-id]`，實際仍為 `[synthetic-reused-id, synthetic-other]`；此結果沒有被算作合格 RED，候選階段未因此放行。

來源原因是既有 `loadMediaThumbnail` 僅以重新排序、但鍵值相同的 Map 更新 `CameraUiState`。Map 的結構相等不包含迭代順序，StateFlow 的相等值 conflation 便不更新儲存的 state 參考。這符合 [Kotlin Map 相等契約](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-map/) 與本專案使用之 [coroutines 1.9.0 StateFlow 來源](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/common/src/flow/StateFlow.kt)。純函式 `touchMediaCacheEntry` 的原有排序測試未涵蓋這個 StateFlow 呼叫端；該原測試原文完全保留。

這是另列的既有 LRU 缺口，不在本批加入修復。原控制來源、實際 key-order assertion 與十一失敗明細保留為獨立證據。新的正向控制僅主張本批未改變的身分／快取 invariant：兩個既有 bitmap 保留、快取命中不發出 backend 讀取；不再宣稱 LRU 呼叫端已正確。未減少任何既有套件的案例或 assertion。

離線 closure 的第一次 UI 編譯另發現未列入的既有 `userFacingCameraErrorResource`。修正為保留其完整目前 `CameraComponents.kt` 雜湊，精確擷取最後一個無 Compose 相依的 top-level 函式，只加原 package／R import。正式資料與 UI 的 120 個檔案重新編譯；此單一函式為額外精確來源片段，**不是整份 CameraComponents／Compose 畫面或乾淨 app build**。六個原產生的 R class 另包為只含 R 的 jar，排除其他舊工具或編譯產物。

## 發布與驗收界線

通過凍結離線門檻與獨立差異審查後，才以正常 hooks、非 force 原 dev 推送，並在推送前重新核對遠端 SHA 與無開啟的 head:dev PR。本批路徑只符合既有 Android host dev validation，預估 10–20 分鐘加排隊；保留原 45 分鐘 job 上限。若前置條件或實際 fanout 改變，停止並重新評估。

Android host 的單元測試／Debug APK／簽章驗證不是裝置或 PR `ci-complete`。此批不啟動 Android emulator/KVM、Apple、Desktop 或 Windows。待確認的原生色彩裝置配額、Apple picker framework crash、尚缺正式契約的 Apple USB／EDSDK／直接上傳等問題不因此結案。修正屬 patch 級候選；本批不調整版本、tag、main 或 release。
