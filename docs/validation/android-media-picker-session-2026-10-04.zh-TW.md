# Android 媒體選檔重建與連線邊界

## 確證的兩個產品問題

產品基準為 PR #194 的 `5a042d2`。先只增加 4 個嚴格回歸，發布 test-only head `f845a5c7b987220149ae3ff466fdb9c0d1c38cc2` 至 PR #198，沒有先改產品或調鬆斷言。

[基準 CI 37195046947](https://github.com/js051/open-eos-control/actions/runs/37195046947) 在 API34 與 API36 各執行 186 例，兩邊都只有下列 2 例失敗，0 error／skip：

- 同一 ViewModel／連線的 composition saved-state 重建後，CreateDocument 結果確實派送，但待下載 item 遺失。即時斷言看到 `active=null, saved=null, error=null`，不是以 timeout 推測。
- 新 ViewModel＋已存 registry／Compose 狀態的程序重建情境：A 發起的舊 upload URI，之後在 B 連線／相簿實際觸發 POST，違反「不能上傳到 B」的斷言。

正常 upload 跨同 ViewModel 重建、一般前景斷線撤銷刪除確認框兩個對照均通過。因此不能把一般 disposed 畫面誤報為跨相機刪除問題，也不能把這兩個失敗歸咎於 fixture。JUnit artifacts 已下載、SHA256 驗證並逐例閱讀。

測試使用真 OpenEosControlApp、ViewModel、Repository 與兩個合成 HTTP peer；只替換外部 picker 為可延後派送的 ActivityResultRegistry，使用 StateRestorationTester 保存實際 launcher keys。這是 Android framework／Compose 邊界證據，不是一次真正 OS 強殺、手機旋轉或真 SAF UI 操作。MainActivity 已處理 orientation，不能說每次旋轉都重建。

## 修正與使用者流程

- 三個 picker 在 App root 無條件註冊，未連線時也能消耗舊 result，不等到新相機的相簿才接回。
- ViewModel 持有唯一 pending request，包含 UUID、原 generation、原 CameraInfo reference 與原選取項目；只有 request ID 進入 saved state，不持久化相機工作。
- 同 ViewModel 重建保留合法請求；程序／session 替換、重連同型號甚至相同 media ID 都不能冒充原連線。
- 回傳先核對並消耗自己的 request，再確認連線與能力，之後才進入任何 resolver／repository 工作。拒絕 stale result 會提示重新選取，不讀來源、不建立檔案，也不刪除系統返回的使用者文件。
- null、重複 callback 與 launcher 例外只清理自己的請求；取消／失敗後可重新選取。同 session 的 Recently／Full card 切換或 refresh 不會誤使合法 pending 過期。
- 已經真正開始的 transfer 沿用原本取消與 partial cleanup，不將 stale callback 拒絕路徑混入下載刪檔。

## 驗證分層

- 10 個新 ticket JVM cases 與 2 個字串檢查通過。
- 最終 aggregate：App JVM **575／575**，Camera Import contract **14／14**，各自 0 failure／error／skip；這兩個數字分開記，不與其他分支測試數相加。
- Lint：0 errors／56 warnings，其中 4 個舊 custom lint registry 未完整執行；沒有 suppression。一般依賴版本提示會隨查閱時間變動，不宣稱本次修正消除了它們。
- App／AndroidTest APK 建置成功，4 分 38 秒。420 個來源檔前後 fingerprint 一致：`ffbae0f819189b771208d86c1447623c7924b5f73b9c258b1a2447703e4abdfc`。
- 原 4 個基準測試函式逐字保留。另 4 例涵蓋三種 stale URI 保護、取消／launch 例外後重選、scope refresh、folder 重建保留 batch 與逐項錯誤結果，共 8 個 instrumented cases 已編譯。
- folder 重建測試刻意返回沒有 DocumentsProvider 的 URI，只驗 callback 與逐項失敗回報；它不是「真 SAF batch 成功」證據。真提供者／跨 App grant 與程序強殺仍需各自的驗收。
- 修正後的精確 head API34／36 CI 尚待執行；基準紅測與本地 JVM／編譯成功不冒充裝置 green。

## Release Assessment

以 v0.10.0 Development Preview 為基準，修正屬 patch。沒有新增相機 endpoint、持續 URI grant、權限、SDK／依賴或版本；沒有實體相機／手機證據。日期篩選、自動傳圖、跨程序續傳不包含在此批，PR ready 仍要求最後精確 head ci-complete。
