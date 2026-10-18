# Android 原始網路測試 500 項收斂

2026-10-18 09:19:28–09:24:38，完整 106 個 Kotlin production/test 檔重新編譯，31 類原 JUnit **500/500 passed**。來源基準 `dcf1c71865c51b83d951d34d756efaad544aaaf5` 加本批兩個測試 fixture 修正；執行前後 source SHA-256 完全一致。

## 原 56 個失敗如何收斂

前批已修正 AUTO Live View geometry owner 與錄影未知 ACK 分類，並保存原 500 項／56 fail。此批只改兩份網路測試，不改 production：

- CcapiClientTest／DesktopBridgeClientTest 使用真 loopback socket，沒有 advanceTimeBy／advanceUntilIdle／runCurrent／backgroundScope／testScheduler／currentTime／TestScope 控制。改由 real-clock runBlocking 執行，60 秒只是外層測試 guard；產品 15 秒 deadline、取消、no-replay、response budget 全不變。避免虛擬時間先耗盡，真 socket 還未 dispatch。
- MockWebServer 4.12 QueueDispatcher.peek 的 throttle 也作用於 request body，完整 request 後才入 takeRequest queue。原 manual 33-byte request 以 1 byte/200 ms 傳輸需約 6.4 秒，超過原 2 秒等待。兩個取消測試改 exact-route Dispatcher，完整 request 後才回原 paced response。保留原 2 秒／700 ms／75 ms 門檻、mandatory release 與所有原斷言。
- 503 strict interceptor 為阻止 OkHttp follow-up 會關閉 response 並拋 IOException，故舊 plaintext body 斷言不適用。改驗精確 `Single-attempt request rejected (HTTP 503).`；Live View AUTO 保留外層 IllegalStateException 並驗其 IOException cause。三案加入 Retry-After:0，保留或加強單次請求／Stop 次數／責任清理斷言，沒有放寬 strict client。
- Maintenance fixture 補合法 current restoreToken 及匹配 started／stopped receipt。same-session-warning 的重試透過原 access.stopLiveView 重新準備 permit。unknown／superseded Start 加上確實送出一次的 requestCount 斷言，避免未送出即失敗的假通過。

原先兩大類候選 183/21 fail、40/14 fail，real-clock 後仍有 5／4 fail，逐一修復後分別 183/183 與 40/40。最終一次完整 500 項綠燈，不把各輪重跑重複加總。

## 證據與驗收界線

[完整回歸證據](evidence/android-network-suite-2026-10-18/) 包含31類log、逐類UTC與結果、106檔hash、完整K2命令/classpath、focused結果與原始log SHA-256。原失敗仍在前批 [證據](evidence/android-liveview-recording-recovery-2026-10-18/)。

這是完整原 Kotlin caller／真 OkHttp loopback 協定 host 執行，沒有替換 production collaborator；仍不是 Android OS／USB硬體／decoder／Compose畫面／真相機驗收。Gradle App、所有185個測試檔、Apple-only build與三端真機尚未全通過。exact-SHA CI、PR ready 不由此推定。

## Release Assessment

`none`：本批只修正測試 fixture／時鐘與驗收文件；不建立版本、tag或release。
