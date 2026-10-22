# Mobile CCAPI 卡槽設定：保留 scoped 讀取的原能力契約

## 範圍與狀態

本批基於 published `9f86613bc0da445ecf59e9c8786488402af77e4e`，對齊既有三平台功能 ledger 第 50 項。僅兩個 mobile adapter、兩份直接測試與本文件；不包含另行封存的 Android USB still／Bulb／half-press 修正。此版本目前是本機候選，兩端卡槽 scoped 驗證已有下列實際結果，但原 Android cancellation regression 仍開放，尚未上傳或觸發 CI。

這不是新的 Canon 協定推測。兩個 mobile 初始 discovery、legacy 寫入及 Desktop scoped 實作，已要求卡槽 `value`／`ability` 保留原字串型別，僅允許 `none`、`card1`、`card2`，至少兩個不同選項，且 current 必須在 ability 中。原 GET／PUT 必須是同一版本的同一資源。

## 實際可達的跨路徑矛盾

1. iOS 初次 discovery 接受合法卡槽資源後，正式 App 使用 `applyShootingSetting` attempt 路徑，而不是 legacy `setSetting`。前者的 fresh prewrite／readback 原先僅走一般字串設定 parser。
2. 使用者對仍合法的 `card2` 發出操作後，若 readback 為 `{"value":"card2","ability":["card1","card2","card3"]}`，一般 parser 會記錄 matched。App 的 `finishShootingSetting` 將完整 observed setting 交給 `capabilities.applyingShootingSetting`，連帶發布 `card3` 選項。
3. 使用者按既有「new」建立新 draft 後，Menu 依現有 choices 顯示 `card3`；若下一次 scoped prewrite 仍回相同非法 ability，membership guard 與 generic payload builder 會容許 `PUT {"value":"card3"}`。這需要原 owner／route 仍有效，且期間沒有完整 capability refresh 移除該資源。完整 refresh 並不是此操作必經步驟。
4. Android scoped parser 亦接受同樣不符卡槽契約的能力，合法 `card2` 請求仍可送出，非法 readback 仍可被標為 matched。但 Android UI observation 只更新 value，不替換 choices；因此沒有把 iOS 的「新增 card3 UI 選項」結論延伸到 Android。
5. Desktop `_scoped_setting_data` 已重用 `_validated_card_selection_setting`，不需要本批 production 修改。

此證據是來源可達的 admission／UI 選項污染，不是實體相機已寫到錯卡，更不是媒體位元內容的證明。

## 最小修正與不可變邊界

兩端只在 `cardselectionstillimage`／`cardselectionmovie` 的 scoped parser 重用既有卡槽 validator，保留原始順序／字串，不新增通用設定框架。兩個 exact key 以外的設定不變。現有 discovery、GET／PUT 同版本配對、原請求路徑、payload、owner、取消、deadline、alias、replay 與 UI production 均不修改。

- 非法 fresh prewrite：維持 NOT_SENT，零 PUT。
- 已完整收到 PUT ACK，後續讀回非法：保留 RESPONSE_RECEIVED，讀回 UNAVAILABLE，不發布 observed setting，不偽造拒絕或成功。
- 已送出但未收到 ACK：UNKNOWN 不得由後續 GET 升級。人工 recovery 僅讀取，不能再 PUT；非法 GET 也不能建立 matched observation。
- 仍可正常選擇原允許且當次宣告的 `none`／`card1`／`card2`，still 與 movie 路由不混用。

## 計畫驗證與誠實平台邊界

預計每端新增 8 方法，使用兩種卡槽的合成回覆：4 個 frozen baseline assertion RED（未知 ability prewrite、單選項 prewrite、ACK 後非法 readback、lost ACK 後非法 manual read），另 4 個 controls。Android 使用真正 CcapiClient、ShootingSettingAccess／Attempt 與既有 loopback MockWebServer；iOS 使用真正 CCAPIClient／Attempt／capabilities helper 與注入的既有 HTTP peer。無實體相機、使用者照片、外部服務或自動重試。

原相關 roster：Android 214 方法；iOS 141 方法。預計 candidate Android 222、iOS 149；方法與向量迭代分開計數，freeze 與 runtime 必須逐一對帳。

Linux 的完整原始 Apple HTTP transport 不能編譯：既有且當前仍存在的 download KVO `NSKeyValueObservation`、`waitsForConnectivity` 等是平台限制，不能以假 API 宣稱原生通過。有限本機 iOS gate 保留原 transport public types／protocol／extension／integrity 的逐位元 prefix，以及真正 client／parser／attempt／capability 來源；排除僅由 default URLSession transport 使用的 platform tasks。首次 Linux compile 另發現 DesktopBridgeClient 的既有 CF 型別僅由 Apple Foundation 匯入；harness 複本只前置 `import CoreFoundation`，其餘 body 與 production 逐位元相同，兩 variant 一致。故確切 closure 是 33 whole production files＋1 import-only body 保真來源＋1 exact transport prefix＋1 unused fail-closed default boundary，不是原完整 Core。未用 default transport 使用嚴格 fail-closed harness 邊界，初始化／呼叫計數必須為零，任何觸及皆令 gate 失敗。原 141 方法中 `CCAPIClientTests.testDirectCCAPIUploadIsExplicitlyUnsupported` 確實建立 default transport，因此本機明列 NOT_RUN，不改原方法、也不算 pass。其他 methods 的 injected peer 可達性須 source seal；預計最多 148 iOS＋222 Android＝370 本機方法，而非湊成 371。

即使有限 iOS gate 通過，也不是 UIKit 點選、Apple URLSession、原完整 Core、real HTTP transport 或實體相機驗收。未來若另行交付，原 Apple workflow 仍須執行完整 Core／App／UI，且既有 picker crash 未解，不能宣稱一次 green 就永久修復。原 UI assertions／timeouts 與 workflows 完全不改。

本批原通知上限：60–90 分鐘 source／review，單次 serial offline ≤12 分鐘，2 CPU／768 MiB sampled process-tree RSS，2 GiB free floor＋768 MiB admission reserve，僅既有工具。runtime 前凍結來源、依賴、fixture、方法集合及精確 RED marker；setup／compile failure 不得當 RED，異常不得改 whitelist 或放寬 assertion。

## 實際驗證結果

2026-10-22 02:59:40–03:11:27 UTC 原 720 秒絕對窗內：

- Android baseline：8 個新方法全執行，精確 4 個 frozen assertion RED＋4 controls。失敗 marker／方法集合逐一對帳。
- Android candidate：222 個方法全執行、221 PASS／1 FAIL，零 ignore／assumption。新增 8 方法及整份 ShootingSettingTransportTest 的 16 方法全部 PASS；不得因局部綠色宣稱完整 regression 通過。
- 唯一失敗是原有 `CcapiClientTest.cancellingHalfPressStillReleasesShutter`：`SocketException: Socket closed`，不是本批預期 RED。原測試及 HeldAutofocusSession 與 base 逐位元相同。該測試在收到 press request 後立即取消，未等 ACK；同步 HTTP execute 被 cancellation watcher 關閉時的 primary exception／structured-concurrency 路徑仍待獨立可控證據。未將它判成無害 flaky，也未改 assertion、retry 或 production timing。
- 第一窗內 Swift 未起跑；其後依下列已通知續行完成 148 個 injected-transport 方法。另 1 個 default-transport 方法仍依前述平台限制明列 NOT_RUN。
- 第一階段曾因缺少原 CcapiClientTest 直接使用的 focusInfoJson／focusInfoPacket helper 而編譯失敗。失敗完整保留，不算 RED。續行納入與 base 相同的整份 CameraFocusMetadataTest.kt，僅作編譯 closure，未選其額外方法；重用已成功且 source/output hash sealed 的 fresh baseline data。未改任何產品來源或測試 assertion。
- 續行仍使用原絕對截止，未重設預算。原 wall 706.87 秒；所有實際命令合計 387.40 秒，最高 sampled process-tree RSS 749,088 KiB。所有 JVM／Swift 子程序已終態；來源與依賴 postcheck hash 全部相符。

### 一次完整剩餘驗證窗

03:17:04 已另外通知 remaining hard 600 秒，實際 03:28:45–03:32:41 UTC，wall 236.26 秒；沒有重設或抹去原 706.87 秒。原始產品／測試 assertion／timeout 都未改，未盲重跑原已綠的 221 方法。

- 原 cancellation 方法使用 sealed baseline/client 與 candidate/client 各執行一次：baseline 再現同樣 `SocketException: Socket closed`；candidate 此次通過原 assertion。這支持基線本來就可暴露該分支，不是卡槽專屬回歸；candidate green 只是非重現，不能稱 cancellation 已修好。失敗方法未走完 release request assertions，不據此宣稱 OFF receipt／physical release 已證。
- Swift baseline：8 方法，精確 4 失敗方法／42 個事前凍結 assertion diagnostics＋4 controls；candidate：148／148 PASS。兩個 phase 的 default transport initialization／request counters 均為 0，零 skip。原 1 個 default-transport 方法 NOT_RUN，不湊 149。
- Swift 初次 compile 因 DesktopBridgeClient 缺 Linux 可見的 CoreFoundation import 而失敗，8.18 秒紀錄保留，不算 RED。上述 import-only closure correction 未改 repo 第六檔或 CF 行為，也不改來源函式 body。原 partial build 只沿用同批、同來源的可重建編譯輸出，兩 variant 隔離。
- 此額外窗所有實際命令合計 94.87 秒；連同第一窗共 482.26 秒實際命令。兩個窗累計 wall 943.13 秒，最高 sampled process-tree RSS 仍為 749,088 KiB。03:33 UTC 檢查無 Java／Swift／compiler 活程序；free 3,416,903,680 bytes，保留 2 GiB floor。來源、依賴、原 evidence 及 staged layout postchecks 全部通過。

卡槽修正的 16 個新增方法（兩端各 8）全部 PASS，兩端各自的 baseline 反例成立。但 Android 原完整 222 方法紀錄仍是 221 PASS／1 FAIL，不能將跨次非重現說成完整 370／371 green，也不是 Apple native、完整 URLSession 或相機驗收。原 cancellation 分支須獨立釐清，不混入本批卡槽 production 修改。兩個 USB patch 仍獨立封存且未交付；本批無上傳、CI 或 workflow 變更。


## 2026-10-22 原 11 檔整合驗證

原先分別封存的 still USB、Bulb／半按釋放、兩端 strict card selection，以及 cancellation test-boundary correction，現依同一個 11 檔 allowlist 在 dev 基底 `9f86613bc0da445ecf59e9c8786488402af77e4e` 整合。7 個 production／test 檔案 bytes 與各自已驗證候選完全一致；沒有加入後來 dedicated-AF 修正或 iOS session-memory 修正，也未修改 workflow、原 UI assertions 或 timeout。

- 實際本機 gate：2026-10-22T09:01:21.520681+00:00 至 2026-10-22T09:08:03.477689+00:00，wall 401.958 秒，保持原 hard 720 秒（710 work＋10 shutdown／seal），沒有重新歸零。實際命令累計 399.899 秒。
- 新編整份 77 data＋43 UI production sources；11 個選取 suite、2 個原始完整 helper sources 加 observer。沒有舊 app jar／source slice／baseline overlay。CameraFocusMetadataTest 的 7 方法僅為編譯 helper closure，沒有選入結果；vectors resource 保持原 SHA。
- 同一最終 run：412／412 PASS，0 failure、ignore、assumption；annotations 與事前 frozen method IDs 一對一核對。集合是 owned-release 188、card 222 與 cancellation 185 的 union，扣除重疊 183，並非把數字直接相加。這是原測試 caller 的共同整合證據，不是原 card 221／1 紅紀錄的抹除。
- 原 108 個 thumbnail／LRU 測試來源保留但未另計入 412；兩個 whole helper、JSON 優先序、backend／repository／VM／各測試 class origin 均核對。合成 loopback fixture 不連真實相機或外部服務。
- 固定 2 CPU，最高 sampled process-tree RSS 745,760 KiB，低於 768 MiB ceiling；admission 採 2 GiB free floor＋512 MiB reserve，runtime 保持 floor。來源、依賴及所有分離候選前後 hashes 相符。
- Android 編譯與 JUnit 整合不是 APK、裝置、KVM、實體 USB／快門驗收。Swift 原 148 injected-transport pass 沿用 exact-source 證據，沒有為增加 green 數字重跑；原 1 個 default-transport 方法仍須 Apple 原生驗證。
- 本節封存時 Android host／Apple CI 尚未執行，不能稱 `ci-complete` 或 PR ready。正常 dev 交付須另核 exact SHA、fresh remote／no open head:dev PR、原 hooks／secret scan、實際 workflow fanout。既有 Apple picker crash 仍未解，任何單次不重現也不能稱永久修復；不得自動 rerun 或擴展 KVM。

整合 method report SHA-256：`251803f81eb0a83d7707d874bd01dd1a5ad1b048ef1640ed64c830c47129582b`；gate outcome SHA-256：`ab68d0bb0c7083ee5b50ef6595e8de548a292ef4dea455c8c8cdd14673660303`；input seal SHA-256：`d78c0916cfd4fb2a9db921c26d5981f89838b4da45dd7f1b8a799f0ec7f2c33a`。驗證後僅本節追加實際整合結果，production／test bytes 不變，原失敗及每批驗證限制均保留。
