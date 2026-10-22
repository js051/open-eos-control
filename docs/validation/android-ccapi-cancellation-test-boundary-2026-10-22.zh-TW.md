# Android CCAPI 取消期間的原 owner release：確定性測試邊界

## 範圍與既有實證

本機候選基於 `9f86613bc0da445ecf59e9c8786488402af77e4e`。僅修改 `CcapiClientTest.kt` 與本驗證文件，沒有 production、timeout、workflow、權限或 protocol 改動；另行封存的 USB 與 mobile card-selection 修正不混入本候選。未上傳或觸發 CI。

卡槽 scoped 驗證的完整 Android 222 方法曾產生 221 PASS／1 FAIL。失敗為原 `cancellingHalfPressStillReleasesShutter` 的 `SocketException: Socket closed`，不是卡槽 assertion。其後相同原方法各執行一次，未改 client baseline 同樣失敗，card candidate 非重現；原紅完整保留，不能用第二次綠清除或稱已修好。

原測試收到 half-press request 後立即取消，未等 HTTP response；它使用未隔離的 child `launch`。真正 `requestOk` 的取消 watcher 會 cancel 正在 blocking execute 的 OkHttp Call，因此原 child 可先拋 I/O 失敗。`HeldAutofocusSession` 有意保留 Start primary error，同時在 `NonCancellable` 中完成同一 owner 的 release，失敗則維持 typed `AutofocusReleaseException`。不能為了測試綠色，將所有 I/O exception 改成 cancellation 或吞掉 release 失敗。

實際 VM 的 reconnect、disconnect、onCleared 都先遞增 camera session generation 再取消原 operation；generic error handler 拒絕已退休 generation。Repository 的 connection mutex 保持原 backend operation 與其 release join。這些有限來源路徑未呈現 stale UI 或跨 owner release 反例，不能因此宣稱所有 production cancellation／physical release 已驗收。

## 修正與不可變邊界

測試將明確收集被取消 child 的結果，而不是假設只可能收到 `CancellationException`。可接受的原 primary error 限 `CancellationException` 或 `IOException`；`AutofocusReleaseException`、assertion failure、fixture/setup failure 都不能被歸成成功取消。

原 half-press／release request assertions 與兩秒等待保留。補 exact discovery＋one press＋one release 的 request 數、child 取消及完成狀態，不能自動重送 press、靠 sleep 或 retry 消除競態。

新增兩個合成、可控的 interleaving：

1. server 已收到原 press，但尚未回 response headers 時取消。
2. public OkHttp EventListener 已觀察完整 zero-body HTTP 204，但其 completion callback 尚未返回時取消。

HTTP response 完整接收不是實體相機狀態、業務成功或真實 ACK 的證明。測試也必須控制原 release response，證明 producer completion 不能先於 matching release 完成。任何 gate／fixture 錯誤必須回到測試執行緒；finally 釋放 gate，避免測試自己留下 producer。

## 計畫驗證

- 保留原 183 方法，僅修正其中一個取消測試的結果觀察邊界，新增 2 方法，總計 185。
- 原真實 baseline 紅使用已封存的 source-pinned receipt，不再次盲跑追隨機失敗；新 gate 執行完整 185 方法一次。
- Fresh 77 production-file closure（74 data／3 UI dependencies）與兩個必要原測試來源。只使用既有 pinned Kotlin／JVM／MockWebServer／JSON 依賴；不以舊 app jar 替代當前來源。
- 本批 source／review 30–45 分鐘，離線預估 5–7 分鐘、hard 600 秒含封存與終止，2 CPU／768 MiB sampled process-tree RSS，2 GiB disk floor 與 stage reserve。
- 不執行 camera、Android device、Apple runtime、KVM、CI 或下載。真正 protocol fixture 只連既有 ephemeral loopback MockWebServer。

## 實際結果

2026-10-22 04:07:53.839–04:12:08.747 UTC，本候選單次 gate 完成，耗時 254.91 秒，未使用剩餘預算重跑：

- Fresh 77-file production closure 編譯成功，67.93 秒；候選測試與整個未改 helper 編譯成功，23.54 秒。
- 完整 `CcapiClientTest` 185 個唯一方法全部 PASS，0 failure／ignored／assumption；其中原 183 方法保留，除本次修正的取消方法外，另外 182 個原 method bodies byte-identical。
- 兩個新 HTTP 時點、原取消方法及其餘原 suite 在同一次 161.96 秒執行通過。確認 matching release 尚未回應時 producer 未完成，原 release 完整回應後才完成；仍不代表 physical camera release。
- 精確 runtime roster、fresh class origins、JSON classpath 優先序及全部來源／依賴 hashes 前後檢查通過。最高取樣 process-tree RSS 758,292 KiB，低於 786,432 KiB；此為取樣總和，不是 kernel hard limit。
- 終態 free space 3,404,124,160 bytes，原 2 GiB floor 未觸發；gate 結束後無本批 runtime producer 殘留。
- Structured test report SHA-256：`c952b63c5f1ae5ab3108bd7e207b41b80e2d4e129cef44848674f63c19de05f9`。

這是原測試的結果觀察與確定性 interleaving 修正，不是 production 行為修正。原卡槽 222 方法的 221 PASS／1 FAIL、後續原 baseline 同例失敗及 candidate 同例非重現，全部保留；不能將本批 baseline 上的 185 PASS 回填成未執行的整合版 222 全綠。卡槽產品修正與本測試修正的整合驗證尚未執行。既有 compile closure setup failures 與明示平台限制不改列 PASS。沒有 push、CI、Android device、Apple native 或實體相機驗收。
