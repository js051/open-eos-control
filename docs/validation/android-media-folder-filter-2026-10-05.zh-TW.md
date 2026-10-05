# Android 已載入素材的資料夾挑選

本批接續產品矩陣 P2「找到並挑出要交付的素材」，在日期、評分與類型篩選上加入相機資料夾。使用者可以找到同一相機路徑中的 RAW＋JPEG、切換到來源未知的素材，再回到全部；切換篩選不丟失隱藏選取，也不增加資料夾查詢或 metadata fan-out。

本批依賴 PR #205 媒體流程及 PR #206 控制旅程，以下記錄送 CI 前的本地驗證；精確裝置結果以本批 PR 最新摘要為準。沒有新增相機卡槽選擇、拍攝目錄設定、建立資料夾或背景傳輸。

## 可驗收的流程與資料契約

- 原生 CCAPI 只從既有成功 listing 的正規化媒體父路徑取得資料夾，info 更新保留原 item 已有資訊；Bridge CCAPI 也可從成功 info 的已驗證路徑取得資料夾，顯示解碼後的相機相對路徑，保留卡片路徑。不同卡片的同名資料夾與同名照片必須分開；不能把 card 字樣解讀為已驗證的實體卡槽。
- Bridge 新增可選的 `folderId`／`folderLabel`，由已有的 CCAPI 路徑或 gphoto2 實際 listing folder heading 產生。ID 對使用端是不透明識別，使用者看到的是相機相對名稱；host capture 的本機路徑不會拿來填這兩欄。
- gphoto2 在缺少 folder heading 時仍可瀏覽照片，但資料夾為未知。`--show-info` 只保留已觀察到的同 session provenance；caller-supplied item ID 不會被當成新資料夾證據。沒有新增 gphoto2 命令或相機 HTTP 請求。
- 欄位必須成對為非空 String，ID 最長 4096、名稱最長 1024 個 UTF-16 code units，不可包含 C0／C1 控制字元。缺漏、型別錯誤或過長只使 provenance 變成未知，不拒絕整張素材。USB、host capture、舊 Bridge／模擬器 payload 沒有權威資料時保持未知。
- 資料夾、日期、評分與照片／影片類型取交集；清除資料夾只清除該條件。選取依原 item ID 保留，批次下載仍包含已選的隱藏項目並顯示既有警示。
- 只統計已載入素材，不保證整張卡或該資料夾的完整數量。選單與已選篩選按相機物件身分及 session generation 隔離，舊選單回呼不能套入新的連線。
- 不連相機的 App 預覽提供同資料夾 RAW＋JPEG 與未知資料夾影片。40 個長名稱資料夾、2 倍字級的選單須可捲動、點擊且每次只套用一次；長名稱可省略顯示，但 accessible label 保留全文。

## 已完成的自動驗證

Android focused 於 2026-10-05 13:42:02–13:45:49 UTC **exit0**：七組 **243/243、0 failure/error/skip**，涵蓋 CCAPI、Bridge client、資料夾、媒體列表、日期、評分及 Preview。來源 manifest 前後一致，SHA-256 `6bba0f8d8d099a2df84ccdaac69733f69cda5213c7929efed576b07327053a97`。這是定向檢查，尚不能代替完整 JVM gate。

Bridge 四個受影響測試檔 **265/265、0 failure/error/skip**，7 秒、exit0；八個修改的 Python 檔 Ruff 通過。先前定向檢查曾 37 通過／1 fixture failure：測試向 FakeRunner 要求未實作的假路徑；改用真有效 item 在另一個未快取 session 驗證未知 provenance，保留只有一次 `--show-info` 命令的斷言。原失敗保留，不當成產品缺陷已修。

第一輪四個 Android UI 案例已編譯，尚未實跑：完整離線 App 的 RAW＋JPEG／未知／全部旅程、跨資料夾隱藏選取與批次精確 ID、40 個長名稱在 2 倍字級下的真 touch、替換 session 關閉舊選單。Gallery 用 lazy key index 驗證排除，而非把 offscreen 當成已篩除。編譯不代表裝置測試通過。獨立審查後再補第五例：2倍字級、資料夾／日期／評分交集與取消全卡載入後的部分資料警示；五例均已編譯，尚待實跑。

Bridge 完整 Python aggregate 隨後 **374/374、0 failure/error/skip**，exit0；全 Bridge 與 scripts/validation 的 Ruff 通過。保留 Starlette 的 httpx deprecation 與 HEAD operation ID 重複警告，未因此更新依賴或聲稱桌面 browser gate 已執行。

完整 Android JVM aggregate 於14:32:54–14:37:44 UTC實際執行 **793/793、70 suites、0 failure/error/skip**，4m49s、exit0，來源 manifest 前後一致（`2d85f687ffed27f09cfa89a274cc75e1359e7afbcaa058544b087d19f84fbb6d`）。隨後只修 AndroidTest fixture：ALL＋CANCELLED 使用 production 的 hasMore=false，讓警示必須由取消狀態成立；standalone 選單採 App 原有 theme／背景。這些測試增量另行重編，不重跑未改的 JVM 來源。

最終 AndroidTest 編譯與 Lint 於14:51:11–14:54:27 UTC完成（3m15s、exit0），Lint **0 error／56 warning／2 information**；新增的一條只是未改 AppCompat 依賴有新版可用的提示，沒有因此改依賴或 suppress。獨立兩APK於14:55:42–14:56:34 UTC完成（51秒、exit0）。來源 manifest 前後一致，SHA-256 `545b61290f3dcbd27273cb93af0e9ea48066d2e14123c5a449bfa5eb034b1c6b`。App APK `9563de32554cb22a81347f725a6e4b10b1b71ea0ea799b5b50a97ba699545ce5`；test APK `6e6e1859c160cef727161abcc57f1dfa273d0be68a9b275c69c70120c97cbfdc`，保留必要旅程class及既有有效H.264 asset。這仍不等同裝置執行。

真 pre-commit 首次拒絕一個人工 Base64-like opaque folder ID（generic-api-key 規則）；它不是憑證，也沒有成功提交。改成明顯 synthetic ID，保留冒號、`%2F`、literal plus 與原值相等斷言，受影響 Bridge client **17/17** 再次通過（29秒、exit0）。產品與 instrumentation／APK 來源未變；793是前一輪完整聚合，此測試字串修改另外由17項及後續精確CI覆蓋。重新執行原 hook，47,991 bytes、0 findings；未改掃描規則或 allowlist。

## 待完成與界線

- 獨立審查已完成八類契約核對，未發現一般旅程 blocker；發現原生與 Bridge 的 contents 前綴資格不同，新增真 listing HTTP fixture 已精確重現：非正式 `/ccapi/ver140/other/contents` 被標成已知根資料夾，單例1/1紅。最小修正先核對原始 `/ccapi/verN/contents` 前綴再解碼顯示；同一例修正後1/1綠，真HTTP請求仍僅2次，34秒exit0並完成五個UI案例編譯。JVM、Bridge aggregate、Lint、兩 APK 與最終 AndroidTest 編譯已通過；仍待精確 head CI。
- API34／36 精確 head UI 實跑尚未執行；須由本批自己的 CI 建立證據，沒有 main 修改。
- 沒有新增物理相機、大卡記憶體、真 gphoto2 裝置或其他機型資料夾命名驗證；optional metadata 不保證所有 transport／機型都有。
- 已載入的資料夾挑選不代表 Camera Connect 全功能對等，也不保證全卡資料夾瀏覽或自動辨識 RAW 配對。

## Release Assessment

- Latest release baseline：`v0.11.0` Development Preview。
- Proposed impact：`minor`，新增使用者可見的資料夾挑選與向後相容的可選 Bridge 欄位。
- 版本未變；精確 CI、相依 PR、main acceptance 及版本 candidate 尚未完成。
- Physical-device status：pending；沒有把 fixture／模擬器結果提升為實機相容性宣稱。
