# Android 離線控制可靠性與 Camera Connect 流程對照

## 本輪目的與界線

以 `1164d3610ddb1da702b024acb87ea75da5f3dcbe`（`v0.10.0` Development Preview）為主線基準，在雲端檢查並修正不需要接觸實體相機即可驗證的控制／傳輸流程。使用真實 App、ViewModel、Repository 與 HTTP client，遠端相機以可控制故障時序的合成 HTTP peer 代替。

目標是防止多送命令、舊連線操作跑到新相機、過期回應回退控制狀態、未確認的釋放被遺忘，以及不完整原檔被報成成功。這不宣稱能離線證明光學合焦、每種韌體的實際行為、無線吞吐或 USB 硬體相容性；過往對焦問題也不因本輪發現而被重新指認根因。

## 原有控制功能的現況

依本基準程式與 `docs/feature-status.md`，Android 已有依後端能力開放的曝光參數、照片／錄影／Bulb、AF-ON／短 AF／半按、觸控對焦、手動 focus drive、Live View、相機回報 AF 框、監看輔助、相簿與原檔下載。可走 direct CCAPI、USB/PTP、Desktop Bridge；功能組合依相機宣告、transport 與實際成功觀測決定。這些是「實作存在」，不等於每個組合已在真機上完成驗收。

容易產生使用落差的具體流程包括：CCAPI 啟用與 Camera Connect 配對不同；不可解碼的 RAW 仍可能下載原檔但無法直接預覽；MediaStore 與 SAF 分流取決於系統接受的格式；按住 AF 和快門保留互斥；來源宣告、命令接受、相機回報 AF 狀態及照片實際清晰是不同證據。這些需要清楚引導與能力提示，不能用一個成功圖示含混表示。

## 已修正的問題與可操作的恢復流程

- **重複相機命令**：CCAPI 寫入使用不自動重試、不可重播的 request body；GET 仍保留讀取恢復。收到 503 或回應斷線不代表可以再次拍照。
- **快門釋放不明**：在 full-press／Bulb start 前記錄原停止動作。失敗後保留 exposure unknown，阻止新曝光及 Live View；畫面提供只送 release 的 RETRY STOP。成功確認後才解鎖。斷線留下「請檢查上一台相機」警告，不把舊 release 送到新相機。
- **Live View 啟動／停止不明**：start 前保留停止責任，stop 失敗不清除；AUTO 不再以 fallback 掩蓋未完成清理。multipart opening 有 10 秒上限與取消監看。
- **換相機與晚回應**：連線世代、受管理工作與 readback revision 共同隔離舊操作。斷線取消並等待舊批次，不讓第二筆刪除／設定跑到替代連線，也不讓舊 status 覆蓋新的 recording 狀態。
- **事件列舉與下載競爭**：先發布相機事件狀態，慢速列檔獨立執行。手動取消或下載會取消並等待實際 listing HTTP，事件輪詢仍可繼續；縮圖與 display 讀取也回應取消。
- **原檔完整性**：CCAPI 與 Android Bridge 對照已知的列檔長度與 HTTP 長度，拒收空檔或短檔。這是長度完整性，不是來源雜湊或可解碼保證。
- **易用性**：相簿 busy 期間停用範圍切換；CCAPI URL 欄補上機身啟用、同網路與 URL 指引；Simulator 不顯示真機 CCAPI 設定指引。危險狀態不靠短暫錯誤訊息表示，警告與停止重試持續可用。

正常快門 autofocus 開關、AF mode、按住 AF 與快門的既有互斥語意未改；沒有新增藍牙配對、自動傳圖或跨程序續傳。

## 官方功能與流程對照

| 流程 | 官方依據 | 此專案現況／本輪處理 | 不應混為一談的限制 |
| --- | --- | --- | --- |
| 連線 | R6 Mark III 的 [Camera Connect 手機連線](https://cam.start.canon/en/C022/manual/html/UG-07_Network_0030.html) 與 [Camera Control API 設定](https://cam.start.canon/en/C022/manual/html/UG-07_Network_0080.html) 是不同機身流程。 | Android 有 CCAPI、USB/PTP、Desktop Bridge；本輪在 CCAPI URL 欄位補上相機端啟用、同網路及 URL 指引，Simulator 模式不顯示真機 CCAPI 設定指引。 | 沒有實作 Camera Connect 的藍牙配對流程；Wi-Fi 本身也不是單一控制協定的名稱。 |
| AF／快門 | [Canon 遠端拍攝說明](https://www.academy.canon-me.com/en/fundamentals/canon-camera-connect-app-connect-your-eos-camera-to-your-smartphone) 展示觸控 AF、AF method、手動對焦與遠端快門。 | 本專案依相機宣告的 API 啟用控制；保留既有 AF 預設語意。本輪聚焦命令只送一次、釋放責任與停止恢復。 | 指令成功回應不等於照片已光學合焦；不據此改 Servo／MF，或解鎖按住 AF 同時拍照。 |
| Live View | [R6 Mark III 手冊](https://cam.start.canon/en/C022/manual/html/UG-07_Network_0030.html) 區分 Wi-Fi 與藍牙 remote；[Canon Academy](https://www.academy.canon-me.com/en/fundamentals/canon-camera-connect-app-connect-your-eos-camera-to-your-smartphone) 明確指出藍牙快門不提供 Live View／設定選項。 | 驗證背景／取消、HTTP headers 不回、start 回應遺失及 stop 失敗；保留停止責任，不讓 AUTO fallback 掩蓋未確認停止。 | 官方消費者說明沒有提供本專案可直接採用的 CCAPI timeout、背景恢復或幀率 SLA。 |
| 瀏覽／選取 | [Canon 說明](https://www.academy.canon-me.com/en/fundamentals/canon-camera-connect-app-connect-your-eos-camera-to-your-smartphone) 描述依日期、評分、資料夾名稱排序，以及日期／檔案類型篩選。 | 本專案有最近／全卡、日期／檔名排序、照片／影片篩選與穩定 ID 多選。本輪修範圍誤標、下載時取消列舉與 session 隔離。 | 依評分／資料夾排序、日期篩選、自動傳圖、藍牙屬功能差異。本專案既有依能力開放的單筆／批次評分寫入，與評分排序／篩選是不同功能。 |
| 原檔下載 | [Android 官方商店](https://play.google.com/store/apps/details?id=jp.co.canon.ic.cameraconnect) 明列不支援原始 RAW 匯入，會轉 JPEG；EOS 拍攝的 MOV／8K 影片、10-bit HEIF 與 RAW movie 不能儲存。[iOS 官方商店](https://apps.apple.com/us/app/canon-camera-connect/id944097177) 對相容機型可開啟優先以 RAW 儲存，匯入 CR2／CR3。 | 本專案保留相機原始位元組，系統接受的格式用 MediaStore，其他用 SAF。本輪讓 direct CCAPI／Android Bridge 都核對原始長度，拒絕空檔；不因官方 Android 的限制而移除本專案原檔能力。 | 「Camera Connect 支援」必須同時說明平台與機型；HTTP 完整、檔案長度一致、SHA-256 來源驗證及 RAW 解碼是不同層次。 |
| 取消／恢復 | [R6 Mark III 手冊](https://cam.start.canon/en/C022/manual/html/UG-07_Network_0030.html) 的相機端傳圖流程可取消；[Android 共用媒體規範](https://developer.android.com/training/data-storage/shared/media#toggle-pending) 提供 pending 後公開機制。 | 保留已確認完成項目；取消正在處理的請求與目的地，禁止舊批次在新連線繼續。未確認快門釋放提供停止重試與獨立斷線警告。 | 相機端 Camera Connect 傳圖時不能拍攝的限制，不能直接套用為所有 CCAPI GET 的通用契約；程序被強殺也不等於已確認停止相機。 |

[Canon 機型能力表](https://app.ssw.imaging-saas.canon/app/en/cc.html) 顯示各 EOS 機型的連線與 USB 功能並不一致。它是官方產品對照來源，不是本專案的已驗證機型清單；本專案仍應以相機實際宣告與對應裝置證據決定相容性。

## 獨立故障模型

測試不假定「HTTP 出錯表示命令沒送到」。合成 peer 可先記錄已收到的命令，再斷開回應；也可拒絕第一次 release、延遲 headers／頁面／狀態回應，或傳回 Content-Length 自洽但比列檔資訊短的原檔。

基準碼已實際重現：

- 一次拍照在回應斷線後收到兩次快門命令。
- Bulb start 不明且補償 release 失敗後，明確 stop 沒有再次釋放；手動 full-press 的未確認 release 也未被 close 重試。
- JPEG Live View start 回應遺失後，沒有補償 stop。
- HTTP 成功但過短／零位元組的原檔仍被視為下載成功。
- 最近項目載入後，MEDIA busy 期間切全卡會把舊資料標為 ALL；回歸測試在舊 guard 上確實失敗，在新 guard 上通過。

相簿最初修正的雲端基準為 514 項 JVM 測試通過、lint 0 errors／57 warnings、App 與 instrumentation APK 建置成功。這是整合擴大可靠性修正之前的結果，不能替代最終版本驗證。

取消 thumbnail／display 的獨立測試也曾在舊碼上失敗：取消後一秒內實際 HTTP 未終止。修正後兩例通過。

### 已觀察到的失敗與修正紀錄

- focused 初輪 35 項為 34 通過／1 失敗。失敗是協程 stacktrace recovery 複製例外，原 cause identity 不在直接 cause；改為沿 cause／suppressed 完整鏈查找原 IOException，仍要求同一原始物件，未放寬控制契約。擴充後 37／37 通過（含 7 個獨立故障測試）。
- 初次整合 JVM 549 項為 547 通過／2 失敗。一個 Live View 舊 fixture 沒回應新增的補償 stop；補上 204 後仍嚴格要求原 HTTP 503／camera busy 錯誤、精確的一次 start＋一次 DELETE、沒有第三個請求。另一個未修改的 HTTP range case 發生 coroutine timeout，未證實根因；不改 timeout 或 range 產品碼，單獨 9 項重測及其後完整 549 項均通過。
- emulator 第一輪相簿 13 項為 11 通過／2 失敗，為測試 setup 在主執行緒呼叫 server.url 導致 DNS 例外；移到 instrumentation 執行緒。
- 新 emulator 上相簿 13 項仍為 11 通過／2 個 setup readiness timeout；當時有啟動遺留的 SystemUI ANR。保留日誌，不據此直接歸因於產品或硬體。沒有調大 15 秒 readiness 上限。
- 控制整合 17 項為 16 通過／1 失敗：320dp／2 倍字級的持續曝光警告確有文字 overflow。保留不截斷、不省略、不中斷 RETRY STOP 的嚴格版面斷言，修正排版後重測。

- 最終 APK 優先批曾因雲端 executor 換鍵而中斷：3 項已通過、第 4 項只記到 started，沒有 runner 結尾。該批不算完成；保留原 log 與 interruption 紀錄，用既有 AVD／相同 APK 受控重啟並另開批次，不改測試上限。

### 最終驗證矩陣

| 範圍 | 本輪結果 | 證據與範圍 |
| --- | --- | --- |
| 最終 JVM aggregate | **552／552 通過**，0 failures／errors／skipped | `reliability-final-552-checks.log` 與 44 個 JUnit XML；包含 6 個 shutter recovery state cases。 |
| lint | **0 errors／61 warnings** | 見下方 registry 限制；沒有 suppress warnings。 |
| App／instrumentation APK | **建置成功** | 同一 aggregate 命令用時 5 分 14 秒；SHA-256 見下表。 |
| 版本與 Python 守門測試 | **33／33 通過**，version 0.10.0 一致 | validation 20、CI provenance 5、release 8；未變更版號。 |
| 獨立故障 focused | **37／37 通過** | 較早 integration snapshot，30 個納入 repo 的測試＋7 個獨立測試，不能與最終 552 相加當成不重複總數。 |
| 本輪 Android framework／Compose／HTTP 目標 | **32 個不同案例均已有通過證據** | 分批、兩組 APK；最後受影響 8 項在最終 APK 全綠，見下列精確區分。 |
| 既有 AF／Live View production-path 回歸 | **20 通過／1 失敗**，0 skipped | 同一重連就緒案例在新舊控制 APK 均於原 8 秒門檻失敗，保留為未解項；不宣稱 21 全綠。 |

最終建置使用的 Android source fingerprint 是 `01b992c68f980654e43a9b67a188fe9fd6681c487e57aa47668486a8b88a656b`；演算法與每檔 SHA-256 在 source manifest 內。

| APK | SHA-256 |
| --- | --- |
| App debug | `29b94835476b676b05ab9fcf816d774cccc332b3760c1a967e37adf4802ad82e` |
| instrumentation | `e32fc4840f468354f0109681e90b7dfd17d8ac99cb0404c1117b400936beb3e0` |

這些是測試用、帶 `.debug` applicationId 的未發布產物，不能冒充主線簽署的 release candidate。

#### Instrumentation 批次與內容

- `reliability-gallery-instrumented`：GalleryUi 4／4、MediaGalleryStore 7／7 通過；該批另有 2 個 GallerySession setup timeout，後來重跑成功，不能把該批 13 項寫成全綠。
- `reliability-sessions-instrumented`：SessionIsolation 5／5、EventRefresh 6／6、LiveViewRecovery 2／2 通過；ShutterRecovery 當時 3／4，失敗的字級版面已修並全類重跑。
- 上述兩批 APK SHA-256：App `f59a53ddae1f1dd6e63f73416f97ad3416c16e9814ce2ac7e0834bed8ee95721`；test `97cf51ad350ef34ab5c6f91f90e1e89c97659eac1d9940b0a202722d1dd9ddc8`。之後產品修改只涉及警告 dismissal 與持續警告排版；這些受影響案例已在最後 APK 重跑。
- `final-priority-instrumented`：平台中斷，沒有完整 batch 結果，保留證據但不納入最終通過計數。
- `final-priority-resumed-instrumented`：最後 APK 的 ShutterRecovery 4／4、GallerySession 2／2、Connection 指引 2／2，**8／8 通過**，runner `OK (8 tests)`。相簿仍使用原 15 秒上限。
- `final-focus-instrumented`：既有 CameraFocusSessionTest **20 通過／1 失敗**，0 skipped。失敗的 `shutterAfSettingTravelsFromProductionUiToHttpAndResetsOnReconnect` 在重連後的 `awaitFrame` 逾時；該條件同時要求有 bitmap 與非 busy。
- `final-focus-reconnect-diagnostic-instrumented`：只增加測試失敗時診斷，產品 APK／production／JVM 測試來源未變，test APK 為 `6f1c31f9ef736729e59c50e170c572ee6a61eb531ddf02a896edf06d08cfb4de`。同一原 8 秒案例再次失敗。timeout 時 connected=true、pending=[CONNECT]、repositoryRunning=true、已送出 Live View start 與首影格 GET，但 bitmap 尚未發布。logcat 同段記錄主執行緒 skipped 325 frames、約 5.96 秒延遲，不能據此宣稱特定協程死鎖。
- `baseline-focus-reconnect-instrumented`：用較早僅有相簿修正、控制仍為基準的 APK 做同 guest A/B；同一原案例也在相同 reconnect／awaitFrame 位置逾時。App SHA-256 為 `68eacc516f58662b8be3b97b853db870a56d2cedcffd63e822b414fab1ad5377`；test 為 `6ecde18a6485e41a7c4a924c36b835661b30a0a5988df6556e8e5704d983ed5d`。這不是純 main build，而是本輪最初的 gallery-only snapshot。

因此目前有 **52 個不同 instrumented cases 的通過證據，1 個既有案例仍失敗**。A/B 支持此現象並非本輪控制變更獨有，但功能與原 8 秒要求仍不能算通過。未改產品碼去迎合尚未證實的原因，也未延長 timeout。加速 emulator 的 exact-head CI 是下一個獨立驗證環境，其結果應另記，不覆寫本次失敗紀錄。

大字級恢復畫面另有實際 Compose PNG 與 TextLayoutResult 診斷：320×640、fontScale=2、警告 3 行、無 overflow／ellipsis，warning bottom 206 小於 retry top 502；已目視檢查文字與停止按鈕。

精確依產品 APK 區分：最後 App `29b948…` 有 28 個不同案例通過、1 個案例失敗；較早 App `f59a53…` 另有 24 個不重複的目標案例通過。兩者合計 52，不代表最後 APK 曾跑完整 53 項。

32 是上述具名目標案例的去重數量，不是全 repo instrumentation suite。全部使用合成相機資料；MediaStore 測試包含 Android framework，FileProvider 目的地不等於真正 SAF picker／跨 App grant。

### 機密掃描工具的跨平台修正

使用者另授權官方 PowerShell／Gitleaks 與最小跨平台掃描支援。保留 Windows 原分支、原 `.gitleaks.toml`、staged／完整 outgoing 範圍、redact 與 fail-closed；Linux x64 使用固定 Gitleaks 8.30.1 tarball，每次驗 checksum 後重新解壓。原 `.githooks/pre-commit`／`pre-push` 入口未被跳過。

主工作樹實跑 **16／16 hook 整合測試通過**：乾淨 index、多 outgoing commits、已被後續刪除的人工 secret、多 refs、未知 remote SHA，以及缺少 PowerShell／scanner、錯誤 hash／下載／解壓／chmod／scanner 執行失敗。全部使用合成 fixture，沒有真實秘密或新 allowlist。Windows 執行時未在本輪實跑；原分支與 checksum 保留。沒有接受額外需要明確同意的協議，也沒有新增憑證或改持續安全設定。

### 工具鏈與限制

使用官方 Temurin 17、Gradle 8.7、Android SDK 35 與 AOSP Android 34 x86_64 emulator；全部位於雲端專案範圍。官方下載依供應方 checksum 驗證。[Google Android SDK 條款](https://developer.android.com/studio/terms)依使用者明確同意接受；未新增其他需要明確接受的協議，沒有改 TLS 驗證或系統信任設定。

最終 lint 為 0 errors／61 warnings；其中 4 個 ObsoleteLintCustomCheck 警告表示 Compose／Lifecycle 的部分自訂 lint registry 需要較新的 lint API，該部分檢查無法執行，不能當作已完整檢查。其餘警告保留，未靠 suppression 清除。本輪不升級整套 AGP／相依版本。

CPU-only emulator 沒有 KVM，不能提供性能／幀率結論。曾發生 guest SystemUI ANR 與一次 emulator 停止回應，已保存紀錄並做受控重啟；測試失敗不會被算成通過。最終受測 API 與各批次內容會分別記錄。

以下仍未驗證或未處理：真機 One-Shot／Servo 光學行為、不同韌體／相機型號、R6 Mark III 大卡與實際弱網、其他手機的 MediaStore／SAF 系統 picker／跨 App grants、USB 硬體、Android API 36 本輪實跑、完整既有所有 UI 排列、PC／iOS。RTP SDP 文字 GET 與少數 identity fallback HTTP 尚未增加主動取消；一般 JSON discovery／事件／媒體讀取已涵蓋。跨程序強殺／斷網後不能由 App 保證相機已停止曝光。

## 證據分層

1. **實作存在**：程式與對應測試檔可檢查。
2. **本輪執行**：以實際命令、測試 XML、lint 與 build 結果為準，記錄基準與最後受測內容。
3. **歷史自動測試／實機紀錄**：保留原日期、版本、手機、相機、transport 與操作範圍，不能拿歷史數字當本輪結果。
4. **Android 模擬器**：驗證 Android framework／Compose／ContentResolver 路徑，不是實體 EOS 或手機硬體驗收。
5. **待實機**：光學結果、One-Shot／Servo、韌體差異、實際弱網／大卡、USB、程序強殺後相機端狀態，仍需相應證據。

## Release Assessment

- Baseline：`v0.10.0` Development Preview。
- 建議 impact：`patch`，修復既有命令、狀態與下載流程；不改正常快門 AF 決策，也不新增 Camera Connect 的藍牙／自動傳圖能力。
- 依使用者追加授權，交付採繁體中文 Conventional Commits 拆分並推修正分支；不合併、改版號或發布。確切 branch／commit／CI 狀態另以交付摘要及 GitHub checks 為準。
- 本地測試不能代替 exact-head `ci-complete`、`main-accepted` 或 preview 發布狀態；真機相容性仍按既有證據分別標示。
- 此文件記錄本地階段：遠端提交的作者公開條件尚待確認，未建立遠端 commit／修正分支；exact-head 雲端 CI 尚未執行。單一既有 instrumentation readiness 失敗仍明列，不因此自稱 PR ready。
