# Android 縮圖快取：將存取順序納入不可變狀態

## 範圍與目前證據

本批接續 `d8b3099e0f6d7d170e988d81f34b126504ea5000` 的縮圖來源歸屬修正，只處理已命中快取的最近使用順序。前批 Android host run `39524555923` 已於 2026-10-21 01:10:01 UTC 成功，包含完整 unit-test task、debug APK 與簽章驗證；該 log 沒有輸出完整單元測試數字，本文件不推算總數，也不為了數字下載包含 APK 的報告。

先前新增的正向控制意外發現另一個既存缺陷，相關舊測試及 11-failure receipt 已保留，未改寫為前批成功證據。`touchMediaCacheEntry` 正確建立重排後的 map，但 Map 的值相等不比較迭代順序；`CameraUiState` 的 data-class equality 與 StateFlow 合併相等值，使 order-only hit 沒有更換已儲存狀態。快取達 96 項後，下一次插入可能淘汰剛使用的 bitmap，而非最久未使用的項目，導致再次進入時多一次讀取與解碼。

這不是新的錯圖／來源歸屬證據，也不表示快取無上限。原有 ID、檔名與視覺分類 signature 仍不是 byte/content identity。裝置上暫時出現 placeholder 是由 UI effect 不以快取存在與否為 key 所推導的可能結果，尚非本批實機觀察。

## 最小狀態修正

- 原 bitmap map、96 項上限與既有 `takeLast(95)` 淘汰方式保持不變。
- `CameraUiState` 增加 `mediaThumbnailCacheOrder`，只保存目前 map keys 的順序，從最舊至最新；data-class equality 因而能看見真正的順序改變。
- 純函式一次產生 map 與其 ordered-ID snapshot，不修改已發布的 map/list，也不設另一個可變 LRU owner。
- 已為最新的項目再次命中仍可由 StateFlow 合併成 no-op，不將每次存取強制變為事件。
- 此 list 最多保存現有 96 個 ID reference，不另存 bitmap、歷史、路徑、檔名或日誌。沒有新傳輸、權限、SDK、全域 timeout 或背景工作。
- cache hit、插入、資訊來源變更、partial/final/event listing、upload refresh、session reset 與 offline preview 都同步維護順序。
- 原 `withDeletedMedia` 保持 byte-identical；兩個實際刪除 caller 既有的 `transitionMediaState` 在 CAS 前正規化 snapshot，CAS 成功後才依原規則關閉被替換的 stream owner。重試 transform 保持純函式。
- 單純取消讀取不改快取順序；原 generation、exact Job、來源 signature、取消、重試與晚到 cleanup 防護不變。

不採用可變 sidecar，避免額外 owner 與每個 reset/filter/delete 的同步責任；不改 Map.equals 契約；也不增加單純為強制通知而存在的 revision counter。

## 可否證測試計畫

原五個相關 suite 的 90 個方法／參數化執行保持原檔 byte-identical。本批新增兩個 test file：

1. `CameraMediaThumbnailRecencyTest`：8 個測試與共用 fixture 僅使用修正前 API，可在 frozen baseline 編譯。
2. `CameraThumbnailCacheStateTest`：10 個 candidate-only state/VM invariant 測試，明確使用新 field；不參與 baseline 編譯。

baseline 預期只有以下四個 assertion RED，另四個控制 PASS：

| 方法 | 指定失敗標記 |
| --- | --- |
| `cacheHitPublishesTwoEntryRecency` | `LRU_HIT_ORDER` |
| `hotBitmapSurvivesNinetySeventhInsertion` | `LRU_HOT_RETENTION` |
| `retainedHotBitmapDoesNotRefetchAfterInsertion` | `LRU_NO_HOT_REFETCH` |
| `repeatedHitsEvictTheOldestUntouchedBitmap` | `LRU_REPEATED_HIT_EVICTION` |

主要案例先建立有效 96 項 linked cache，透過實際 VM 命中最舊項目，再透過 repository/backend 與 Robolectric bitmap decode 插入第 97 項。候選必須仍為 96 項、保留剛命中的同一 bitmap、淘汰原第二項，且只讀取新項目。另有分開的後續命中案例驗證不會重新讀取被保留的 bitmap，避免前一個 assertion 遮蔽後續效果。

四個未受影響控制是：目前 MRU no-op、stale/missing identity no-touch、取消另一讀取不改既有快取、未變更 listing 保留快取與順序。fixture 的有效 map 先經一次實際 MRU caller 正規化，避免人工注入 state 缺少新欄位而造成不實 no-op 失敗。

十個候選 invariant 涵蓋：相同 map pairs 不同順序必須是不同 state、舊 snapshot 不變、MRU no-op、重複命中與插入不產生重複／歷史 ID、metadata 保留與來源變更清除、partial/final/event/empty listing、實際 preview 刪除發布邊界、session/offline reset、synthetic confirmed upload 的 refresh 清除。刪除只走 preview 的 synthetic receipt；upload 只讀 fixture 自建的本機一像素 JPEG，由受控 backend 回傳明確 receipt，不接觸相機或外部網路。

## 離線 gate 與限制

本批事前通知包含 source／fixture／harness 準備與獨立 review 約 45–60 分鐘，以及一次最多 8 分鐘離線 gate（預期 4–6 分鐘，含編譯、有限診斷與收尾裕度）。限制為 2 CPU、取樣 process-tree RSS 768 MiB、shared free-space 2 GiB floor 及每階段 output reserve；RSS 是觀測 guard，不是假稱 kernel hard limit。超出時間或資源即停止，不自動延長。

`CameraUiState` constructor/copy ABI 已改變，因此 baseline 與 candidate 的整組 UI source 及各自 tests 必須分開 fresh compile，不使用前批 VM-only jar overlay。共用 data output 只能來自本次 fresh compile、且 source 必須與兩邊完全相同。沿用已驗證的 77 data + 43 UI whole-source closure 與精確擷取的單一 error-resource helper，執行前重新核對 closure、helper 原始 bytes、R-only 六個 class、既有 synthetic JPEG、dependency hash、完整 method roster 與 classpath provenance。

此 closure 不等於完整 CameraComponents/Compose screen、乾淨 Gradle app build、裝置或 PR `ci-complete`。既有完整 Android host dev workflow 才另外執行正式 unit-test task、debug APK 與 signer verification。compile/setup/fixture failure 不算 RED；只有指定四個 assertion failure 可接受，其他結果原樣保留並停止判讀。

### 實際離線結果（2026-10-21 UTC）

- 唯一一次 gate：01:59:53.792296 開始，02:03:24.601765 結束，**PASS**，共 210.810226 秒，低於事前通知 480 秒上限；沒有重新起算、追加窗口、setup 修補或 assertions 調整。
- Frozen baseline：8 個全數完成，精確四個上述 assertion RED、四個控制 PASS；沒有 compile/setup failure 冒充 RED。
- Candidate：**108/108 PASS**，包含原檔未變更的 90 個執行與 18 個新案例；71 plain +37 Robolectric。逐一核對方法 ID、開始／結束、failures、ignore 與 assumption，沒有 skip 或 assumption acceptance。
- 共用 fresh source group 編譯 72.046153 秒；baseline whole-UI 編譯 48.568120 秒；candidate whole-UI 編譯 53.027022 秒。兩邊的 CameraUiState、CameraViewModel、OfflinePreview class 均來自自己的 fresh UI jar；candidate 不使用 baseline UI jar。
- 最大取樣 process-tree RSS：758,588 KiB，低於 768 MiB；沒有 guard stop。結束時 shared free space 2,404,024,320 bytes，維持 2 GiB floor；沒有清除其他專案輸出。
- Offline result receipt SHA256：`c49a3cf3e934f07dca56f39ec4915aad92cb746d51a8b506960c4a39f696e5ec`。
- Baseline method report SHA256：`217dd4783e3ae5c8ac202bde7cfeb5656004d39bffe4e98714673dc58907f784`。
- Candidate plain／Robolectric report SHA256：`b240e810366465b538cb5fb7a9e9a86d5dfacdcf4c4ce40e8e9ba6164979853a`／`4287242101f8e3181cfb17bc2be35a5b098e5d081835fc1df8e531b98deda0e7`。

交付前只更新本段真實結果，production/test bytes 與已通過的 frozen candidate 完全相同。這些數字是離線 scoped gate，不是完整 Android Gradle suite 的總數；本批完整 Android host CI 在 push 後另行驗證，尚不宣稱成功。

## 交付與未涵蓋事項

預定只改三個 Android production files、兩個新 Android test files 與本文件，共六檔。正常 non-force dev push 前重新確認 remote head、沒有開啟的 head:dev PR、精確 path fanout、正常 hooks 與 source hash。預期僅觸發既有 Android host dev validation，約 10–20 分鐘加 queue，45 分鐘 job timeout 不變。

不觸發 Apple、Desktop、Windows、Android emulator/KVM；不改 workflow、安全／必要檢查、main、release；不安裝或下載套件。待批准的 native-color 第五組 KVM、未解的 Apple picker framework crash、Apple wire／SDK／upload contract 等既有界線不因本批而解除。
