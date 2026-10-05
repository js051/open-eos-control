# Android 素材日期範圍：本地篩選與驗收界線

## 基準與範圍

程式基準是已接受的 `3af7df40c3f998835eb755d55701f85177714ed8`（2026-10-05 發布的 v0.11.0 Development Preview，tree `099eafaa0e68bba65dc846919b4ba067989c6d6a`）。本批對應產品缺口矩陣的 P2 素材日期範圍；新增範圍輸入、已載入內容篩選與必要的畫面狀態保護。

新增功能只作用於已載入素材；不新增相機 endpoint、相機寫入、metadata fan-out、全卡自動掃描、Android permission 或依賴。沒有改動下載 job、Close 清理責任、相機排序或拍後選圖演算法；不包含評分篩選、資料夾整理、跨程序續傳。版本更新與發布不在本批範圍內。

## 日期契約

`captureTime` 不是一律來自相機原始拍攝時鐘：CCAPI 讀取 `lastmodifieddate`；PTP 可提供不含時區的本地日期時間；Android USB host 素材檔案則以 `lastModified` 轉成 UTC `Instant`。因此功能稱為「素材日期」，不能將所有值都宣稱為相機原始拍攝日。

- 兩端必填 `YYYY-MM-DD`，起訖兩日都包含。單日範圍合法；反向、任一空白或無效日曆日期不可套用，亦不會被當成清除。
- 完整 timestamp 必須嚴格解析；有 offset／UTC／zone 的值轉為畫面明示的裝置顯示時區。無 offset 的時間沿用既有 details 的裝置本地語意。
- ISO date-only 值保留其日曆日期；詳細資料只顯示日期，不創造午夜、offset 或不存在的時間。compact 支援既有 PTP 的 `uuuuMMdd'T'HHmmss` 完整時間，不接受任意日期字首。
- 篩選與分組使用同一個明確的 `ZoneId`；有效詳細資料維持既有裝置時區顯示。無效完整日期不再被詳細資料或分組正規化為看似有效日期。既有 `toMediaInstant` 排序 helper 不變。
- 缺少或無效日期屬於 unknown；啟用範圍時排除並顯示數量，清除範圍後仍可瀏覽這些項目。

跨午夜例子：`2026-08-14T00:30:00+08:00` 在 UTC 的 details／分組／篩選皆為 8 月 13 日；`2026-08-14T23:30:00-07:00` 在 UTC 皆為 8 月 15 日；USB host 形式的 `2026-08-13T16:30:00Z` 在 Asia/Taipei 皆為 8 月 14 日。無時區時間並不是已知相機時區，介面有明示此限制。

## 操作與 session

- 現有 Material3 元件提供直排起訖欄位，沒有預填今天。草稿只屬於 dialog；取消或 Back 不改已套用範圍，只有套用／清除才送出狀態更新。大字與有限高度下表單可捲動。
- 範圍由 ViewModel 持有：同 VM 的 composition 重建與離開再回相簿保留；新 VM 預設無範圍，切換／重連相機與離線展示會清除。未持久化至磁碟。
- 媒體 UI 的 generation 僅供畫面 identity；沒有取代 transport 的既有 private generation。相簿暫態 UI 以 generation 加 `CameraInfo` reference identity 分隔，避免相等欄位與重複檔案 ID 冒充舊畫面。Apply callback 抵達 ViewModel 時仍比對原 identity／generation。
- 日期摘要包含已載入數、符合數、未知日期排除數與顯示時區。Recent、部分載入、取消／失敗列表仍明示尚未搜尋其他卡片內容；範圍不會自行要求 Full card 或額外 metadata。
- 篩選不重新建立素材或合併 ID，RAW＋JPEG 保留各自 ID 與相對順序。已隱藏的選取仍保留；固定短提示明示批次包含多少隱藏項目，捲動日期說明不會把這個提示捲走。刪除確認另含完整警告。全選／取消全選只變更目前顯示項目；離開選取則清除所有選取。
- 下載被篩選隱藏時，原 owner、名稱、進度、取消入口及精確 bytes 流程不變。拍後 review 仍以原 helper 找新素材；若開啟的素材在範圍外，viewer 以單張 1/1 顯示且不導航到其他項目。

## 驗證分層

最終工作樹的完整本地 aggregate 於 2026-10-05 03:02:01–03:07:58 UTC 執行，exit `0`，Gradle 顯示 `BUILD SUCCESSFUL in 5m 56s`（79 tasks：20 executed、59 up-to-date）。首次 aggregate 在環境重新啟動時中斷，沒有 terminal 結果；下列數字全部採用同一來源的 recovery aggregate，不沿用較早的 34 例 focused checkpoint。

```text
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
--max-workers=2 -PlocalDebugApplicationIdSuffix=true
```

- App JVM：本次產生的 XML 共 58 suites、663 tests，0 failures／errors／skipped。包括 `MediaDateRangeTest` 9 例、`MediaDateRangeStateTest` 5 例及既有 `MediaLibraryTest` 25 例。
- `lintDebug`：0 errors、54 warnings，XML 另有 2 information；沒有將 warnings 宣稱為零。既有 custom-registry fatal gate 維持啟用。
- App debug APK 與 instrumentation APK 均組建成功；`compileDebugAndroidTestKotlin` 在本次 aggregate 實際執行。Instrumentation 的組建成功不代表測試已執行。
- 以 `LC_ALL=C.UTF-8` 對 `android/app/src` 的所有 `.kt`／`.xml` 相對路徑排序，建立逐檔 SHA-256 manifest；執行前後逐 byte 相同，其 SHA-256 均為 `c0425fdd2155f36b8668a365b97e3a0bd86340b885c687bde3886cae30a2e42f`。此為工作樹來源 fingerprint，不是 commit SHA。原編譯記錄中的 `MediaScreen.kt:441` 冗餘 null-check warning 尚存，未為清理 warning 改動已驗證來源。

本地 debug 產物僅作驗證，沒有取代正式 release candidate：

| 產物 | Application ID | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `android/app/build/outputs/apk/debug/app-debug.apk` | `dev.openeos.control.debug` | 19,193,460 | `9393dd4f09549e7c24ccd447af1fefd65766b26c16580627e86daa42606874b1` |
| `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `dev.openeos.control.debug.test` | 1,560,982 | `5718b7318985a039d798fc7e8befece7cda42075073ee8881fae96c400fbb6f0` |

本批新增或擴充的 instrumented 覆蓋：

- 真 App → CameraActions → ViewModel → Repository → 合成 HTTP peer：範圍套用／取消／清除、RAW＋JPEG、未知日期、沒有新增 listing／camera write、隱藏選取與精確刪除 ID、同 VM restoration／重連／stale Apply、新 VM、範圍外 review、真原檔下載中切換範圍且精確 bytes 不變。
- production MediaScreen：同欄位但不同 reference 的連線在同一畫面中替代、舊 draft／selection／delete dialog 撤銷；零結果與 CANCELLED／FAILED 的 partial 提示、固定 hidden count、下載 callback 的精確 ID；清除日期不改種類與排序。
- 既有完整拍攝／原檔保存旅程加入排除新素材的日期範圍，再真正執行拍攝路徑，檢查新檔仍能 1/1 開啟與保存。
- Dialog 先完成真 Activity 橫向與 layout 才放 content；2 倍字體下分別驗 Apply／Cancel／Clear，另有實測 320×320 dp 約束。檢查完整測量 bounds 位於祖先裁切與真 Android 視窗交集內，再單次 physical tap，不以純 `ForcedSize` 或 accessibility hittable 當成功。

本機 `adb devices` 無連接裝置，且沒有 `/dev/kvm`；沒有安裝新模擬器。本批 instrumentation 只編譯，沒有在此環境執行，不聲稱 API34／36 green、真相機、真手機、IME／系統鍵盤或真旋轉 Activity recreation 已驗證。StateRestorationTester 只代表 composition saved-state restoration。畫面 session 邊界的新增測試尚未實際跑成基準 red→green，亦不宣稱修復未驗證的相鄰 queued-delete race。

## Release Assessment

最新發布基準為 2026-10-05 的 v0.11.0 Development Preview。本批為新使用者可見能力，建議 impact 為 minor；不在這個分支變更版本。PR ready 仍要求此變更精確 head 的 ci-complete；API34／36 instrumentation 與真機時區／素材來源仍待各自驗收。後續進版前須重新核實最新發布基準與精確 head 驗收。
