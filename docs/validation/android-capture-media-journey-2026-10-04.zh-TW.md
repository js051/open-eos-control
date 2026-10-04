# Android 拍攝、最近素材與預覽保存旅程

本批把已有拍攝、最近素材、原檔保存串成可恢復的操作流程。這是 Android 雲端工程驗證；沒有新增實體 EOS／手機操作，也不表示全產品已完成或與 Camera Connect 全功能等同。

## 使用者流程與失敗恢復

1. 拍照命令確認後查詢最近素材。命令確認、相機狀態讀回、可見素材是三種證據；不把任何一者冒稱實體曝光成功。
2. 有限查找仍未看到與先前 ID 不同的素材時，顯示尚未找到及只讀重查。舊圖可以明確開啟，但不畫成本次新圖；重查不發任何相機寫入。
3. 開啟最近素材的預覽，直接保存支援格式的原檔；其他格式沿用選取文件／資料夾。
4. 預覽保持開啟，顯示原檔進度、取消、保存位置、失敗與明確重試。忙碌時下載鍵仍可辨識但停用，避免重複傳輸。
5. 取消或完整性驗證失敗移除本次 partial，沒有成功提示；明確重試只重新下載既有素材，不再拍照。
6. 同名不同 ID、換相機 session、舊 HTTP 回應不能冒用另一筆成功。切到其他素材仍可取消正在保存的項目；關閉預覽不隱式取消既有下載。

## 整合來源與依賴

基底為 PR #200 `a9e1474a261111f3333c8c0c26d1d881d267bac1`，承接 #199／#194。以逐筆、有來源紀錄的套用帶入：

- #197：`2697cfcfd275fe085798dbd77d5a4d38bcf5e85e`，事件提示直到完整狀態同步成功才消耗。
- #198：`f845a5c7b987220149ae3ff466fdb9c0d1c38cc2` 的因果測試與 `a60d49689cad2662df420afd3a2ffad9e7bfa2c6` 的 picker session／重建修復。
- 三筆保留原 commit message 與 cherry-picked-from 來源，沒有用任一分支整份 ViewModel 覆蓋其他工作。

第一個交集衝突位於 event catch：保留 #200 的 `ShutterReleaseException`／停止風險同步，再保留 #197 pending changed keys 與 1／2／5 秒退避。15項來源比對通過，#200 六個關鍵方法／interlocks 及 #198 picker 方法／helpers 保留。這個整合基底同樹已跑 App609與import-contract14測試，AndroidTest APK成功。

保存與拍後確認分開提交。兩者整合時，只合併 session reset 的獨立欄位，以及英文／繁中各自新增字串；兩側值全部保留，資源 key 無重複。新增來源／測試逐檔比對相同，保存 request owner 與拍後 attempt owner 沒有互相取代。

提交 PR 以 #200 為 base，包含上述 #197／#198 差異的明確來源。它不是 main 更新或對原 PR 的合併；後續若先接受原分支，應以最終樹重新核對差異，不盲目重複套用。

## 因果證據

### 已確認命令與狀態讀回

獨立 MockWebServer 經真 `CameraViewModel → CameraRepository → CcapiClient`：初始四例僅 Simulator `POST /ccapi/capture/still` ACK之後 `GET /ccapi/status` 503會讓 review reads=0；正常 ACK／status、快門503不查媒體、native optional battery503仍找到新 ID 三個控制皆過。Native多數status子讀取是optional，**未將Simulator反例泛化成Canon真機缺陷**。

Typed readback failure只包住已完成命令及必要release後的獨立狀態讀取；取消、未確認命令、釋放失敗仍保留原語意。額外 revision reconciliation 讀取失敗也不抹去先前ACK。新素材不清除狀態警告，只有成功的新狀態證據可清除。

### 重查與保存互斥交集

新增手動重查最初只檢查CAPTURE忙碌，未包含MEDIA。gated真metadata GET保持MEDIA時，紅測看到listing從5次增加為6次。新入口及其按鈕現在共同檢查MEDIA；其間不新增listing，MEDIA完成後原待辦可重查。沒有擴改既有全部拍攝／傳輸互斥語意。

### 預覽內保存

舊UI的下載進度、取消、位置與錯誤在全螢幕Dialog後方。新增按media ID的typed feedback，使用request、generation與connection reference核對所有保存進度／結果／清理更新。單檔SAF、批次SAF、MediaStore沿用既有原檔pipeline；Serein流程獨立。

`CameraPreviewSaveJourneyTest` 使用真Compose／VM／HTTP／MediaStore，preview JPEG與original bytes刻意不同，保存thumbnail不能通過。五個案例涵蓋拍一次→開最新→存原檔、非零進度取消／重複busy tap、完整HTTP但違反原檔長度後明確重試、同名不同ID／重連、舊peer晚回應。最後一例確認舊job在replacement前已完成；它證明late peer隔離，不冒稱舊finally在新session之後執行。

純owner predicate三個JVM案例直接測production更新採用的條件，包括同session新request、退休owner、generation變更、equal但不同reference的camera／null。

## UI 驗收的範圍

- 關閉／下載header、影像pane、有界可捲動保存footer分開，保存時metadata移入footer，避免重複扣除影像底部空間。
- Zoom reset位於自身viewport內，讓開右側導覽；新測試量真viewer內容2:1 bounds，核對close／download／next／reset／cancel在內容內且不相交，並實際雙擊縮放及取消。
- 保存的4個UI案例與拍後確認的3個UI案例分開。拍後確認使用父層ForcedSize／locale／2倍文字檢查callback、scroll、MEDIA busy與offline；父配置不能單獨證明Dialog的真實視窗尺寸，沒有把它算成物理旋轉驗證。
- 新同樹UI／HTTP／MediaStore案例必須由API34與API36 CI實際執行。只有Kotlin編譯不表示通過，最終精確head及原始XML結果記在提交PR。

## 有界查找與保留的契約

既有最近8候選、250／750／1,500ms重查間隔、近期相簿60+1與原檔完整性規則不變。**8是候選數，不是HTTP請求總上限**。Native fixture宣告1,000頁，第一頁給8候選，實際page請求非空且只到第1頁；多container、素材種類、info hydration仍可能增加請求。Simulator fetch-all-then-take不作大卡pagination證據。

沒有新增拍攝命令、相機端點、AF模式、正常快門期限、權限、持續URI grant、跨程序續傳、自動傳圖或RAW引擎。查到不同可見ID不能確證它由這次快門產生，UI明示此限制。

## 驗證與交付

- 保存focused：既有67＋新owner3 JVM通過；5個production instrumentation與4個UI案例編譯。
- 拍後確認focused：新13＋既有45 JVM通過；3個Compose案例編譯。修正Main test teardown，取消children完成後才resetMain，防止suite互相污染。
- 所有本地提交使用真pre-commit；推送前實際outgoing範圍必須真pre-push成功，remote每筆tree／parent／message／作者另核對。
- 最終整合production tree：App **625/625**（53 suites）新執行，全無failure/error/skip；contract **14/14**未變更、Gradle UP-TO-DATE沿用本批先前實跑。Lint、App與AndroidTest APK同次5m6s成功。174個Android來源／設定前後fingerprint相同：`a9c7238525cb8570e24dbf509f357688e4a8aeab9fc7473801583667967574ba`。精確遠端head CI／雙API XML與未跑checks以提交PR為準，不跨分支加總歷史數字。
- Lint **0 errors／54 warnings**；已知4個Android custom lint registry版本不相容：Lint無error不代表這些未執行checks通過；不增加suppression。

## 產品邊界與下一步

依[既有產品流程／Canon官方比較快照](https://github.com/js051/open-eos-control/blob/08b28a9db2712a80b2a517968234af44784a58b6/docs/product-workflow-acceptance.zh-TW.md)，本批改善的是完整拍攝／媒體旅程；仍不等於Camera Connect藍牙連線、自動傳圖、日期／評分篩選與各機型相容性全部完成。Android繼續作為完整App主線。Camera Import消費端的Catalog、RAW／JPEG非破壞性編修與輸出仍屬Serein邊界。

Release Assessment：基準v0.10.0 Development Preview；本批預覽內保存回饋為新增可感操作能力，整批建議impact為minor，拍後確認修復本身為patch。未改版號／發行通道。只有精確head `ci-complete`成功才可標PR ready；main accepted與preview released是後續獨立狀態。實體EOS／手機、真SAF provider與跨機型儲存路徑仍待驗，沒有新增物理相機證據。
