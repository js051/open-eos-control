# Android 前景 JPEG 自動匯入驗收

初始日期：2026-10-06 UTC；2026-10-07 整合已發布 v0.12.0 的 accepted main `95d6def9cb0ad57b7a40c73cc601540f0a195707`。原三筆功能提交以 `7e4e7cc869e745c3a6e47501f2990714112a8ac7` 為基底並完整保留。本批對應[原產品矩陣](../product-workflow-acceptance.zh-TW.md)的 P2「拍後自動交付」，是一個新的明確啟用、限本次連線的前景工作流。

目前為本地候選，尚無本批 PR／精確 head CI。下列實作與測試來源不能提前當作 Android runtime 通過、main accepted 或物理相機驗證；最終本地測試結果另列於末節。本輪正常合入已發布的 SAF／USB／viewer 主線內容，未帶入未發布的 USB 驗收文件後續差異。舊來源的通過數不能代替新整合來源的 gate。

## 可交付的使用流程

1. Android 10 以上，以直接 CCAPI 連線且實際具有媒體瀏覽、下載能力。首次從相簿開啟匯入說明；控制畫面只在匯入啟用後、有停止結果或待處理清理警告時顯示狀態入口與對應操作。只有開啟說明不會啟用。
2. 主層直接說明新 JPEG 原檔範圍、舊檔不補匯入、來源不能證明本次拍攝、相簿目的地、前景／連線條件、停止及清理風險；兩次清單、檔案大小與容量數字放在預設收合的「運作方式與限制」。展開或收合不會啟用、讀取相機或改變清理警告。查看說明後必須另按啟用；離線預覽、USB、Desktop Bridge、缺少能力或尚有未確認清理警告時，不可啟用。
3. 系統取得兩次完整、有上限且 ID 集合一致的 baseline。啟用時已有的檔案全部排除，不補傳舊檔。
4. 只考慮 baseline 之後新觀察到的 JPEG ID；RAW 同伴、影片、相同 ID 的重複回應不增加傳輸。相同檔名、不同 ID 仍是不同項目。
5. 只對新候選讀新鮮 metadata；至少兩次正數大小一致且相隔一秒，才送入原檔保存。相簿顯示待處理、已完成、略過及停止原因。
6. 原檔串流進既有 MediaStore pending row，完整寫入與關閉、長度驗證成功才公開，並保留既有下載紀錄的完成收據。顯示預覽的 bytes 不會代替原檔。
7. 手動停止、App 背景、斷線或換 session 會停止發現／排隊，取消所屬 HTTP 與輸出，等待清理結束。回到前景或重啟仍是關閉；再次啟用會建立新的 baseline。

## 來源、容量與重複規則

- 「新」只表示同一次明確啟用後，完整觀察結果中新出現的 ID。它不證明素材來自某次快門、這支手機或這次拍攝；不使用檔名、時間戳或快門 ACK 代替因果證據。
- 兩次 baseline 的上限為 2,000 項；ID 集合不同、回應不完整、超限或格式衝突即停止，不能以較短的最近清單冒充全卡。
- 啟用期間最多記憶 4,096 個 ID，不因檔案消失、完成或 RAW 被略過而遺忘。整次觀察有界遍歷所有回傳容器，另外受 512 次 container request 預算限制；未耗盡的遍歷顯示不完整並停止。
- 每次後續觀察最多要求 4,097 個 ID，以偵測超限；排隊加正在保存最多 20 張 JPEG。超限的整批先拒絕，再進行任何新候選 metadata 或原檔請求，不偷偷只保存前幾張。
- 以上是保守、完整遍歷的觀察模型，不是相機提供的原子卡片快照。拍攝期間卡片內容變化、ID 重用及大卡效能仍需要實機證據。
- 只有新候選有 metadata fan-out，舊 baseline 檔案不補讀。每次 metadata 請求清除舊 size/content type fallback，缺少新大小不能形成穩定證據。
- 去重只在本次 opt-in 內，未持久化 ID、排隊或自動啟用狀態；不承諾跨程序續傳或跨 session 下載去重。

## 控制、失敗與清理

- 背景匯入在取得下一個媒體操作之前讓出已在進行的手動工作。正在讀取或保存的媒體操作具有互斥保護；不承諾一般手動設定可以搶占一個已開始的原檔傳輸。
- 已在錄影／已擁有 Bulb 的安全停止仍可操作：先取消本次匯入操作，再送既有 Stop。Stop 不等待本機 Gallery 刪除結束，也不重送開始／拍攝命令。
- 新自動匯入路徑只做一次 canonical original GET。HTTP retry、redirect、Authenticator follow-up、非 2xx 後再取、display fallback 及外層媒體重試均禁用；原有手動下載行為不因本批改變。
- 保存失敗不重送原檔或控制命令。已公開的原檔即使 Stop／後續記錄回應競爭，仍計為完成；未公開輸出不得計成功。
- 停止中維持 STOPPING，直到所屬操作與 output cleanup 結束。新連線等待舊 owner 清理，不讓舊 HTTP／收據更新新 session。
- 無法確認刪除 partial row 時保留醒目的清理警告。只持久化警告 Boolean，沒有路徑、URI、相機 ID 或啟用設定；新 ViewModel／新連線也能看到。
- 使用者確認已了解清理警告只清除警告，不刪除任何檔案、不開始匯入；必須另外明確啟用。程序被終止時不能保證 pending row 已被清掉，既有未完成紀錄仍不能當成功。

## 測試來源與斷言

### JVM／合成 HTTP

- `ForegroundJpegImportPolicyTest`：baseline、RAW/JPEG、duplicate、兩次新大小、未知大小、容量拒絕、publication/Stop、清理未確認與同 ID 衝突。
- `ForegroundJpegImportRunnerTest`：雙 baseline、完整 listing、超限前零 metadata、忙碌讓出、取消、read failure、一次原檔、已公開成功與停止清理。
- `CameraForegroundJpegImportTest`：真 repository/HTTP 配合受控輸出，連線／前背景／取消 ownership、清理 gate、LAZY 取消、既有 recording/owned-Bulb Stop 到達相機先於 Gallery 清理、零重播及 late warning。
- `CcapiMediaInventoryTest`：頁面／容器／多卡觀察、上限、malformed/conflicting source、沒有舊檔 metadata fan-out、單次 original HTTP 與 body/integrity 失敗。

### Android instrumented 來源

- `CameraForegroundImportJourneyTest` 的 7 個旅程走 production Compose → ViewModel → repository → MockWebServer → 真 MediaStore，使用唯一合成 folder／history，清理僅限測試建立的 row。
- 覆蓋開啟／取消說明不啟用、舊檔不回灌、RAW/JPEG、重複觀察、一秒新大小、original 與 display bytes 不同、完成紀錄、下載後冷啟動仍關閉、真實 Activity ON_STOP/ON_START、明確重新 baseline。
- 真 MediaStore wrapper 僅攔截當次建立的 URI，測試 pending-row 取消、錯誤長度／503 單次 GET、已 publication 後 Stop 競爭、delete failure 警告跨新 VM，以及 acknowledgement 零刪除／零啟用。
- `ForegroundJpegImportUiTest` 的 20 個案例覆蓋能力、狀態與停止、清理警告／確認、新 session disclosure 隔離，Debug 正常 active／terminal 入口、清理未退役時不可確認、詳細說明切換零操作副作用，以及英繁中 2 倍字級下主層風險與展開／收合後的可捲動動作、完整文字和觸控邊界。
- JVM fake output 可驗 operation ownership，不能代替上述 MediaStore instrumented runtime。編譯成功也不代表這些 OS 旅程已通過。

## 本地及遠端驗收狀態

- 首輪 policy 17/17、後續 data/policy 整合 233/233 均通過，來源 manifest 一致。這些是各階段來源，不與最終測試累加。
- 首輪 owner 整合 Gradle 115/115 通過，但測試 teardown 在執行期間被修改，manifest 抓到一檔變動；沒有當作最終同來源驗收，且未接續 AndroidTest 編譯。
- 2026-10-06 08:05 UTC 凍結後 focused：116/116 通過，來源 manifest 無變動；包含新增 owned-Bulb Stop 回歸。同來源 AndroidTest 編譯也成功。最終獨立 review 隨後修正 Debug 狀態／Stop 可見性與 owner 退役前 acknowledgement 按鈕可按性，新增對應測試且重審無剩餘 blocker；修正後完整 JVM 已 883/883 通過，74 份 XML 均於 08:21 UTC 新產生，來源 manifest 無變動。後續 AndroidTest 因新文字 helper 多寫 `onAllNodes` import 編譯失敗；它是 rule 成員而非 extension，僅移除此測試 import，未修改 production/JVM 來源。Lint 在該輪未執行，最終 compile／Lint 與精確 head API34/36 runtime 待補。
- 08:28 UTC 的 compile／Lint 後續輪次已越過 `compileDebugAndroidTestKotlin` 至 `lintAnalyzeDebug`，但工具拒絕後 session 消失，沒有 terminal exit 或新 Lint XML。僅記錄已觀察到的編譯進度，整輪／Lint 保留 unknown；未反覆重跑、未替換必要 gate，也未沿用其他分支結果。
- 本地沒有 KVM／加速 AVD，不以 CPU-only emulator 重跑取代必要 CI。遠端驗收必須使用本批 exact head，不能沿用其他 PR 的全綠結果。

## 2026-10-07 主線整合

正常合入 accepted main `95d6def9cb0ad57b7a40c73cc601540f0a195707`；production 自動合併，產品矩陣的一處相鄰章節衝突保留雙方公開紀錄，並分開標示歷史快照與本批候選。整合提交 `7198b697e66de3dda2a10f748af6e76d4a9696d6`（tree `1674f366b6f7262bf24e6eedf9859d85375c2498`）在 04:16:27 UTC 完成完整 app JVM 929/929、Camera Import contract 14/14、AndroidTest Kotlin／Java 編譯及 fresh Lint（0 error、63 warning、2 information），536 份 tracked source 在整輪前後一致。

上述結果屬說明介面收斂前的主線整合來源。後續四檔 UI／文案／instrumented 測試收斂已保留原 19 案並增加 1 案，最終來源仍需本地 gate 與精確 head API34／36；不能把已通過整合來源直接當作修改後 runtime。先前兩次 compile／Lint unknown 原樣保留，不回頭重試該 tool action。

## Release Assessment

- 最新發佈基準：`v0.12.0` Development Preview。
- 建議 impact：`minor`，理由為新的使用者可見、明確啟用之自動交付能力。此文件不改版本、不建立 release 或授權 merge。
- 未閉合項目：本批完整本地 gate、Android API34/36 exact-head CI、實體手機／相機的來源與容量／網路行為證據。
- 物理裝置狀態：沒有新增。合成 HTTP／MediaStore 成功不代表 Canon 機身、USB、Bridge、外部 SAF、iOS 或 PC 同等可用。
