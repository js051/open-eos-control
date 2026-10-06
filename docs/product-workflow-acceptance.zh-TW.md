# Open EOS Control 完整產品缺口與驗收排序

稽核日期：2026-10-04。程式基準：`5a042d24724ca2f4d2b5dc2988d2e9ef99711c6f`，即 PR #194 本次取得的 head。本文是該基準的產品流程稽核快照；各修正分支的測試與交付另記，不將進行中的工作計入完成。

## 後續交付狀態（2026-10-04 UTC）

以下補記目前交付狀態；後面的原始結論、缺口矩陣、程式行號與驗收契約保留上述基準，不把已完成的修正重新列為當前 blocker，也不回寫歷史測試結果。

- PR #194 已以 [`28dc3d2`](https://github.com/js051/open-eos-control/commit/28dc3d2db0a3f9a295931419098f1c6c3bc0bff7) squash 接受；其 tree 與本頁基準 `5a042d2` 相同。
- PR #199 已以 [`f3e9871`](https://github.com/js051/open-eos-control/commit/f3e9871588a1e8c25fbb571f4b504ad5ebd3e76d) 達到 main accepted，完成下方第一批契約的 Bridge CCAPI／PC 停止責任與恢復入口。開始回應不明時保留原 session 的 release 責任，停止重試不重送開始；[故障與 UI 驗收紀錄](validation/bridge-bulb-stop-recovery-2026-10-04.zh-TW.md)保留各來源提交的測試邊界。
- PR #200 已以 [`76d7ab4`](https://github.com/js051/open-eos-control/commit/76d7ab46a7c9eac1d0a90edd507e01102c9c868d) 達到 main accepted，補齊 [Android Bridge 停止恢復](validation/android-bridge-shutter-recovery-2026-10-04.zh-TW.md)。只有同次 Stop 後的明確新證據才解除風險；額外 status 補讀有 5 秒 whole-call 上限，原 Stop 保護與預算不變。
- PR #203 已以 [`b61c837`](https://github.com/js051/open-eos-control/commit/b61c8372414474fbbce934d1eb5a636717c9a737) 達到 [main accepted](https://github.com/js051/open-eos-control/actions/runs/37238311008)，整合 Android [事件狀態恢復](validation/android-event-state-recovery-2026-10-04.zh-TW.md)、[picker session／重建邊界](validation/android-media-picker-session-2026-10-04.zh-TW.md)、[拍後確認與預覽原檔保存](validation/android-capture-media-journey-2026-10-04.zh-TW.md)。來源 head `42066cb69ea6f632eafd6829f7f4faf290dd0ec6` 的 [CI 37236220311](https://github.com/js051/open-eos-control/actions/runs/37236220311) 成功，API 34／36 各 211 例通過，含各 13 個拍攝／保存旅程案例；此為該精確 head 的結果，不跨分支加總。
- PR #201 已以 [`d70de29`](https://github.com/js051/open-eos-control/commit/d70de29115320570d5f8fef3fcfc8ffcc79d0e4b) 達到 [main accepted](https://github.com/js051/open-eos-control/actions/runs/37240097659)，完成 [iOS direct CCAPI／Bridge 的 Bulb 停止責任與 App 恢復入口](validation/ios-bulb-stop-recovery-2026-10-04.zh-TW.md)。來源 head `419ed6372ae902080263025cd3d4e816a5c2950c` 的 [CI 37238805887](https://github.com/js051/open-eos-control/actions/runs/37238805887) 成功：Core 228、App unit 90、UI 15 例均由該次 run 實際通過；未受影響的平台 job 依路徑分類跳過。此為本補記已接受的 main 基準。

以上閉合的是各批明列的停止恢復與 Android 拍攝／保存範圍，不構成所有命令、gphoto2／EDSDK 或其他平台旅程的同等保證。真相機快門、光學對焦、實體手機／SAF provider、跨程序續傳與跨機型相容性仍依各自證據判定；沒有新增物理裝置驗證或發版。

## Android 媒體與控制旅程進度（2026-10-05 UTC）

PR #205 將日期／評分挑片與最近 100 筆原檔下載紀錄合為一個媒體批次：原檔成功關閉／發佈後隔離取消與紀錄故障，重啟未完成項目顯示結果未確認。精確 head `1c672d5f18d084576fba23c2ffff518e5f4749d6` 的 [CI 37302730469](https://github.com/js051/open-eos-control/actions/runs/37302730469) 已全數通過適用 gate，API34／36 原始 XML 各264/264、0 failure/error/skip。這是 `PR ready` 證據，PR 仍為 draft／未合併；本地725 JVM與各階段測試修正另見[媒體 CI 紀錄](validation/android-media-journey-device-ci-2026-10-05.zh-TW.md)及 PR 摘要。歷史失敗保留，不能把所有輪次相加。

控制旅程 PR #206 以 #205 為基底，整合連線設定歸屬、取消／失敗恢復及停錄後新可見影片查找。最終 head `cddab6207aa2d5fcb721e7cef85f1cdba64b7fd1` 的 [CI 37323715956](https://github.com/js051/open-eos-control/actions/runs/37323715956) 與 ci-complete 成功：API34／36各278/278、0 failure/error/skip，含兩個真 App 連線、兩個有效影片保存旅程，以及完整文字與三種真裁切／省略號反例。JVM／APK／signer及適用gate通過；本地完整JVM為776項。歷史CPU-only timeout及前兩輪失敗仍保留，不倒推其唯一根因。此為PR ready證據，仍draft／未合併；來源與紅綠機制見[控制旅程整合驗收](validation/android-control-journey-2026-10-05.zh-TW.md)及PR最終摘要。

資料夾挑選 PR #207 接續P2，僅使用既有已載入素材的權威路徑資訊，與日期／評分／類型交集並保留隱藏選取。新增optional Bridge欄位不增加相機請求，USB／host／legacy無資料時保持未知；不包含拍攝卡槽或目錄mutation。最終 head `601c801f56dd5530817204559d12ec3afafeaa03` 的 [CI 37338824937](https://github.com/js051/open-eos-control/actions/runs/37338824937) 與 ci-complete 成功，API34／36原始XML各283/283、0 failure/error/skip，包含五個新增資料夾旅程；本地793 JVM與374 Bridge測試另保留。這是PR ready證據，仍draft／未合併。第一輪兩個fixture前提錯誤及API36另三個history Back失敗保留，最終通過不倒推原平台根因。見[資料夾挑選驗收](validation/android-media-folder-filter-2026-10-05.zh-TW.md)及PR最終摘要。

SAF 保存接續P2原檔目的地旅程：已重現並修正一般resolver刪除不符DocumentsProvider契約、拒刪後自動重試累積輸出，以及取消時遺失清理風險。輸出所有權定向26例JVM已通過，實際DocumentsUI／獨立test APK提供者已編譯；整合#207後同樹808 JVM、Lint與兩APK通過；裝置驗收尚待完成。詳見[SAF清理驗收](validation/android-saf-cleanup-2026-10-05.zh-TW.md)，不將編譯當作跨UID執行或實體provider證據。

上述均是有界增量：不代表跨程序續傳、完整佇列恢復、下載去重、全卡素材因果或跨平台完成；真正相機／手機網路／USB／外部 SAF provider 尚待各自驗證。下方矩陣仍保留原始稽核基準，日期／評分與下載紀錄缺口的最新狀態以上述 #205 為準。

## 結論

專案已有可執行的拍攝、監看、相簿與傳輸基礎，不能再用「有沒有某個按鈕」評估完成度。接下來應依「可靠控制 → 完整拍攝流程 → 相簿與傳輸 → 易用性與相容性」交付，逐條驗證使用者在錯誤、取消、背景與換相機後仍能完成工作。

優先批次是 **Android 單次事件狀態讀取失敗後的恢復** 與 **Desktop Bridge 的 CCAPI Bulb 未確認開始與停止恢復**，各自保持獨立分支與驗收。Android 已消耗的事件若遇到一次權威讀取失敗，後續空事件不會重新同步（`CameraViewModel.kt:2796–2827`）；應保留待同步責任，以有上限的退避重讀，不重播命令。目前 Bridge 與 iOS direct CCAPI 都在 `full_press` 成功回應後才保存 active 狀態；若相機收到開始、回應遺失、補償 release 再失敗，後續 stop 與 close 可能不再送出 release。這是可從程式路徑界定的風險；故障重現、修復及精確提交驗證由各交付紀錄佐證，不是新真機發現。它比新增遠端拍攝選項更優先，且只需使用既有已文件化的 press/release 命令。

## 原始目標與目前承諾

- 最初 `a4eb9e8:README.md:3–5` 指定 R6 Mark III 優先的開源監看／控制工具，靈感包含 ZineControl、Monitor+；先做 CCAPI Wi-Fi，之後才加入 USB、UVC/HDMI、LUT、波形、偽色、斑馬紋與峰值對焦。
- `c281878:README.md:3–5,66–68` 隨即改為 Android 手機／平板直接連相機，並要求真實 Canon endpoint 與 R6 Mark III 驗證，不能把 simulator-friendly 實作當穩定相機產品。
- 現行 `docs/architecture.md:3,29–45,50–54` 擴充為 Android／iOS／PC 共用控制概念；Android 仍是第一個完整 App，功能由實際 transport 與能力決定，不從型號猜命令。
- 原先規劃的 LUT／波形／偽色／斑馬紋／峰值對焦及 PC UVC/HDMI 已有實作，不應列為尚未開工；持續幀率與實機相容性仍待驗證。見 `README.zh-TW.md:43–49`、`docs/feature-status.md:51,53–54,115`。
- 現行版本仍為 Development Preview，README 明確不建議正式拍攝使用；「完整產品」不是 CI 全綠、端點數量或盲目複製 Camera Connect 全部功能。見 `README.zh-TW.md:5–11`、`AGENTS.md` 的 Delivery States／Release Decisions。

## 官方對照已重新查閱

以下均於 2026-10-04 開啟 Canon 官方公開頁面；這是官方產品行為比較，不是 Open EOS Control 的相容性證據。

1. [Camera Connect 機型功能表](https://app.ssw.imaging-saas.canon/app/en/cc.html)：R6 Mark III 列有無線／USB 傳圖與 Live View 遙控、位置資訊、藍牙、自動傳圖、韌體傳送與 Wi-Fi 資訊設定。不同機型組合不同；不可把此表轉成我們的 capability allowlist。
2. [R6 Mark III 手機連線手冊](https://cam.start.canon/en/C022/manual/html/UG-07_Network_0030.html)：描述藍牙配對後切 Wi-Fi、影像瀏覽／刪除／評分、RAW 效果處理、拍後自動傳圖、日期時間、韌體更新與關機後維持連線。其相機端傳圖流程可取消，但傳圖時不能拍攝；這不能直接推導所有 CCAPI GET 都禁止拍攝。
3. [R6 Mark III CCAPI 設定](https://cam.start.canon/en/C022/manual/html/UG-07_Network_0080.html)：CCAPI 是另外的 HTTP 控制流程，需要在相機選擇 Camera Control API、配置網路／驗證並使用機身顯示的 URL。Camera Connect 藍牙配對成功不代表 CCAPI 已可連線。
4. [Canon Academy 操作說明](https://www.academy.canon-me.com/en/fundamentals/canon-camera-connect-app-connect-your-eos-camera-to-your-smartphone)：遠端控制涵蓋曝光、AF 點／方法與手動對焦；媒體可依日期、評分、資料夾排序，並依日期／檔案類型篩選。藍牙快門不提供 Live View 或設定選項。
5. [Canon SDK 公開介紹](https://www.usa.canon.com/support/sdk)：EDSDK 與跨平台 CCAPI 提供控制與影像傳輸的開發介面，但公開行銷介紹不是私有 ABI／完整端點契約。本次沒有取得或推測新的 Canon 私有合約；公開 Function List 頁面只回傳載入／CSS 錯誤，未拿搜尋摘要當完整規格。
6. [Canon ID 官方 FAQ](https://app.ssw.imaging-saas.canon/app/en/canon-id.html)：Camera Connect 3.4.0 起需要 Canon ID，已登入者可在登入有效期間離線使用。本專案的本機／直連控制流程沒有理由因此新增 Canon 帳號依賴。

舊報告引用 Android／iOS 商店的 RAW 匯入差異；本次限定 Canon 公開來源，未重新核查兩個商店版本，故不把舊平台格式清單當今日結論。原檔傳輸、預覽解碼與 RAW 顯影仍應各自驗收。

## 按使用流程排序的缺口矩陣

P1 是可能使拍攝工作受阻或失去安全停止能力的高優先項；P2 是完成工作與資料取回；P3 是降低入門／跨平台成本。Research／Device 是證據或外部依賴，不表示可以拿模擬器補足。

| 優先與使用旅程 | 已實作的基礎與平台差異 | 可證缺口／風險 | 可獨立的雲端驗收 | 外部界線 |
| --- | --- | --- | --- | --- |
| **P1 安全停止曝光與對焦**：按下、回應不明、停止、再拍 | Android direct CCAPI 已有持續 release 責任、停止重試與新 session 隔離。Bridge CCAPI／iOS 有 best-effort cleanup，但 Bulb active 只在 start ACK 後設置；短 AF／半按的 helper 也沒有持續停止責任。[A] | start 已生效但回應遺失、cleanup 又失敗時，Bridge／iOS 的 stop 與 close 都可略過 release；下一次拍攝不能因本機顯示 inactive 就被當成安全。 | 真 production client 接合成 peer：收到一次 full_press 後斷回應、拒絕第一次 release；狀態仍須表明結果未知、阻止新曝光，明確 retry 只送 release，ACK 後才解除。close 仍盡力清理，舊責任不能送給新相機。加入 PC UI→Bridge HTTP 回歸；iOS direct 另批驗同故障。 | 物理快門是否關閉仍需相機端確認；斷網／程序被終止不能承諾清理成功。不要先解鎖 held AF 與快門互斥。 |
| **P1 長時間連線仍可信**：Live View、機身轉盤、背景／重連後繼續 | Android 近期修正寫入不重播、讀取取消、過期回應、首影格責任；三端已有 event polling、權威 readback 與 AUTO fallback。[B] | 尚不能因 Android 修正就聲稱 iOS／Bridge 有同等故障耐受；事件中斷、慢首影格或停止失敗可能造成舊畫面／忙碌／狀態落後。不同 source 的清理責任須逐一路徑稽核。 | 斷開 event poll、延遲 readback、阻塞 frame headers/body、背景／快速 off→on／換 session；驗證仍可停止、舊回應不覆寫新狀態、取消結束真 HTTP，且 AUTO 只在舊來源清理已確認後降級。Android 事件中斷恢復由另批交付，完成前不預先記成功。 | RTP 歷史 R6 III start 為 503；這既不是已驗證可用，也不是所有韌體永久不支援。手機網路共存、弱網與持續 FPS 需要真機。 |
| **P1 完成一次可信拍攝**：選模式、構圖、對焦、拍攝／REC、檢視新素材 | 三端有 Photo／Video、曝光、Tap AF、focus drive、Bulb、REC、拍後小量 recent refresh；Android 有 shutter AF 開關與 held AF，PC／iOS 未有該範圍等效完成聲明。[C] | 命令 ACK、相機 AF 狀態、確實產生新檔與照片光學清晰是不同結果。核心 still／REC 真機驗收仍未補齊，不能拿 UI／simulator 全綠取代。 | 固定 fixture 跑完整生產路徑：mode 拒絕應回復確認值；capture ACK 後新素材延遲到達、thumbnail 失敗、RAW+JPEG 配對與舊素材重現，不得重拍、顯示錯誤「新照片」或把成功快門改成失敗；REC stop 與停止後媒體刷新亦覆蓋。 | 需要同手機／build／韌體／鏡頭記錄 still、影片開始停止、卡槽／拍攝目的地、One-Shot／Servo／MF 與真實影像結果。光學問題根因仍未確認。 |
| **P2 找到並挑出要交付的素材**：全卡／最近、篩選、多選、預覽 | 三端已有最近／全卡、日期／名稱／相機順序、照片／影片篩選、預覽／影片播放、評分寫入（能力允許時）。Android 相簿 busy／listing-download 競爭已修。[D] | 日期區間篩選、評分排序／篩選、資料夾整理仍缺；「能寫評分」不等於能按評分找照片。現有資料不足時也不能把 unknown rating 當 0。 | 可先做只用既有 timestamp 的日期範圍功能：邊界、未知日期、時區、RAW+JPEG、多選 ID 穩定、部分載入明示與取消後計數；評分／資料夾功能先確認資料來源，不能新增無界 metadata fan-out。 | 大卡延遲／記憶體與 Canon 實際排序仍要真機；CCAPI／USB／gphoto2 不一定都有相同 metadata。 |
| **P2 原檔確實到達目的地**：單張／批次下載、取消、重啟後知道完成什麼 | Android 有 pending MediaStore／SAF 原檔，iOS 為 file-backed URLSession，PC 為串流／支援時暫存保護的目的檔。Android PR #194 加強長度與 session 取消。平台實作不能相互代言。[E] | 尚未宣稱跨程序可續傳；取消／程序強殺後的可恢復工作清單、重複下載處理與真正 picker／跨 App grant 仍不完整或未驗證。長度吻合不是來源 hash／可解碼保證。 | 分批做可回復的傳輸狀態：只保存完成與未完成的真實結果；partial 不公開、不算成功；以已確認下載檔案／項目 ID 避免誤覆寫。斷回應、磁碟不足、grant 失效、取消、冷啟動等須有確切失敗與恢復斷言。不先承諾任意 backend HTTP Range 續傳。 | Android 系統 SAF picker、其他手機儲存提供者及實體 iPhone 匯出仍需相應平台驗證；沒有能力證據的格式只能下載，不得顯示可預覽。 |
| **P2 拍後自動交付**：拍照後不必每次手動找檔 | 最近照片入口、event hints、能力限定的 host-RAM capture 與下載已存在。這些不等同 Camera Connect 的 auto transfer。[F] | 尚無已驗收的拍後自動傳圖工作流；若直接對每次事件重掃／重傳，可能造成重複與拍攝競爭。 | 後續可用已支援 listing/download 做前景、明確啟用的 bounded auto-import：baseline 不回灌舊檔、事件去重、延遲成檔、RAW+JPEG、忙碌、取消與 session 替換。先定義目的地／格式與重複規則，無須猜藍牙協定。 | 背景長駐、相機關機後喚醒、官方 auto-send 的相同體驗不包含在此範圍；需 OS／相機證據，且傳輸策略應由使用者啟用。 |
| **P3 能連得上且明白限制**：首次設定、選 transport、遇錯恢復 | Android 已有 CCAPI URL 指引、USB 診斷、Bridge scan；三端有英文／繁中、能力與 session evidence。PC Windows USB 仍需已有 gphoto2／WSL／usbipd；iOS 已實作 Wi-Fi 與 Bridge。[G] | Camera Connect 配對、CCAPI 啟用、直連 USB、電腦 Bridge 容易混淆；隱藏 unsupported 控制若沒有原因說明，也易被誤認成壞掉。文件存在過時狀態，不利準確承諾。 | 用無相機／endpoint 缺失／錯 URL／401／不支援 USB／Bridge 掃描空結果等 fixtures 驗證可理解的下一步與能力理由；不導向關 TLS 驗證，不記秘密。更新流程狀態與證據日期，保持英繁中、大字級與觸控可達。 | 不能用其他平台測試聲稱 iPhone／Windows USB 可用；新增安裝、授權／安全設定另行取得同意。 |
| **Research 相容性擴充**：換相機、iPhone 直連 USB、EDSDK、BLE／GPS／韌體 | R6 Mark III 是 golden target；其他型號走能力證據。iOS USB/PTP 與真 EDSDK provider 明列 Research。[H] | Canon 官方支援某機型不等於開源後端已支援。BLE 配對／關機喚醒、GPS 回寫、韌體更新與 RAW 顯影都不能因官方 App 存在便推測實作契約。 | 可離線完成 capability fixture／拒絕未知設定與相容性報告格式；研究只接受可取得、授權適當的文件與 API。每新增 backend／能力需先有 executable path 與負向測試。 | 真機、Apple 平台 API、合法 SDK／ABI、附件、韌體版本及權限均為外部 gate；不列為下一個無依賴功能分支。 |

### 矩陣程式與文件證據

- **[A]** `bridge/open_eos_bridge/ccapi.py:1704–1737`（Bulb）、`:957–974`（close）、`:2725–2747`（短命令 cleanup）；`ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIClient.swift:1228–1268`、`:544–550`、`:3691–3717`。Android 對照 `docs/validation/android-offline-reliability-2026-10-03.zh-TW.md:19–27`。`docs/feature-status.md:60` 尚有早期「ordinary full-press/Bulb parity not claimed」範圍，不能覆蓋掉較新的 Android 修正證據。
- **[B]** `docs/validation/android-read-cancellation-followup-2026-10-03.zh-TW.md:24–59`；`docs/feature-status.md:33,50,72–73,120`；`docs/validation/eos-r6-mark-iii-android-ccapi.md:26–28`。
- **[C]** `docs/feature-status.md:52,55–65`；`docs/validation/eos-r6-mark-iii-android-ccapi.md:29–31`。最近項目／拍後重試已存在，並非待新增功能。
- **[D]** `android/app/src/main/java/dev/openeos/control/ui/MediaLibrary.kt:15–17,70–97`；`ios/OpenEOSControl/App/MediaLibrary.swift:39–77`；`bridge/open_eos_bridge/static/media-library.js:39–65`；`docs/validation/android-gallery-download-coordination-2026-10-03.zh-TW.md:9–23`；Canon Academy 官方排序／篩選說明。
- **[E]** `docs/feature-status.md:18,67–69`；`docs/validation/android-offline-reliability-2026-10-03.zh-TW.md:24,27,106,122,130`。本次讀取程式未發現可聲稱整個產品已跨程序續傳的證據，不將單次串流重試當成續傳功能。
- **[F]** `docs/feature-status.md:65,88,110`；`docs/validation/android-offline-reliability-2026-10-03.zh-TW.md:27,36–40`；Canon R6 Mark III 手冊的 Automatic Image Transfer。
- **[G]** `README.zh-TW.md:30–42,74–85`；`docs/control-transports.md:46–62`；`docs/architecture.md:53,65–73`。
- **[H]** `docs/architecture.md:31,41–42,71–73`；`docs/feature-status.md:118,121,125–135`。

## 第一批有界交付的驗收契約

**題目：Bridge CCAPI Bulb 未確認開始與停止恢復。** 修正使用者可遇到的開始／停止責任，包含必要的狀態傳遞與 PC 恢復入口；不只改 backend flag 便宣稱流程完整。

1. 獨立 peer 先記錄已收到 `full_press`，再使回應遺失；首個補償 `release` 失敗。必須先證明基準會漏掉後續 stop，保留紅測試證據。
2. 原始停止責任在任何 start 發送前保存。未知結果期間禁止再次開始曝光、一般相機寫入與 Live View 恢復；停止入口持續可操作。
3. 使用者重試只發送原 session 的 `release`，不重新發送 `full_press`。release 失敗仍保留責任；只有成功確認才清除，且不得把未知結果記成成功 observed feature。
4. 回應／狀態 refresh 失敗不得抹去 release 責任；停止 ACK 已成功但後續 status 失敗，也不得因此再啟動曝光或把確定停止寫成仍在曝光。
5. close 會對未確認的原責任盡力 release；失敗對使用者可見，替代 session 不承接舊命令。連點／並行要求仍只會產生一次曝光開始。
6. 覆蓋真 Bridge HTTP 與 PC UI 的錯誤→可停止→停止成功流程，並檢查 Android／iOS Bridge client 對新增或既有狀態的解析與可操作性；如果這些客戶端尚未提供同等恢復 UI，就明列未完成，不宣稱三端已完成。
7. 聚合檢查、必要 browser tests 與精確 head `ci-complete` 通過才是 PR ready。此批建議 impact 為 `patch`；不得自行合併、發版或把模擬 peer 當物理快門驗證。iOS direct CCAPI 相同問題可以另批修正。

## 狀態與文件維護

- `docs/feature-status.md` 保留 2026-08-14 的原始全表稽核日期，另列本次流程稽核，避免把歷史功能與裝置紀錄提升為今日全平台完成。
- `docs/control-transports.md` 的舊 host-RAM hidden 描述已依目前 `gphoto2.py:1695–1743,2638–2645` 與 `test_gphoto2.py:934–1060` 校正。這是既有功能文件修正，沒有新增 USB 支援或實機證據。
- 官方頁面可能更新，本文的功能表只代表查閱日期。相容性聲明仍需相機型號、韌體、鏡頭／附件、平台、transport、App build、實際操作與 operator-confirmed 物理結果。
- PR #194 的既有 CPU-only 失敗、後續加速 CI 通過及最後 head 的結果必須各保留原基準；本文不重新裁決或覆寫那些歷史驗證。核心原則是區分「程式存在」「本次測試通過」「裝置功能觀察」「使用者確定物理結果」。
