# Android 原始協定 JVM 測試恢復與 Live View fixture 修正

## 執行結果

2026-10-18，來源基準 `8e2bc47a244cc77a5cd4ab81e503b4d310b5e9c9`。

- 08:44：75 個完整 production／test Kotlin 檔以原 Kotlin 2.2.21、JDK 17 編譯通過。包含完整 CcapiClient、DesktopBridgeClient、USB backend；沒有擷取函式、替換狀態機或手寫 collaborator 取代 production。
- 首輪 72 個原 JUnit：70 passed／2 failed。DirectRelease 13、FocusAck 21、MediaDeleteHttp 5、UsbPtpObservedFolders 25 全過；LiveViewControl 8 中 2 失敗。
- 兩個失敗都在 Live View deadline 後的 Stop retry／fixture cleanup，連向 localhost 的另一個 IPv6 `::1` 位址而被拒。deadline 與 pending Stop 的原斷言已通過；不是把產品 Stop 責任清掉來繞過失敗。
- 唯一程式修正：LiveViewControl 的 MockWebServer 明確綁 `127.0.0.1`，client origin 同樣使用該 IPv4 位址。保留 strict no-replay client、15 秒 whole-call cap、250 ms shorter deadline、所有 cancellation/join/owner/assertions。
- 08:47:37：修正後完整 75 檔再編譯通過，LiveViewControl **8/8 passed**。其餘 64 項的 production／test bytes 未改，未重跑計入新進度。此批合計 **72 個不同案例通過**；原始兩個失敗亦保存。

## 證據邊界

這是 standalone JVM 對原始 Kotlin 檔與原 JUnit 的執行。OkHttp／MockWebServer 透過本地 socket；USB backend 透過原測試的合成 PTP containers。Android SDK 35 `android.jar` 供型別連結，沒有以它宣稱 Android OS、USB hardware、decoder、GPU、Compose 或真相機執行。

完整 Gradle App build／全部 unit suite、instrumentation、exact-SHA CI、實體裝置仍未通過。此批不能等同三平台完全對齊或 PR ready。

## 可重現材料

[證據目錄](evidence/android-protocol-jvm-2026-10-18/) 包含：

- `native-source-files.json` 與 `manifest.json`：75 檔精確清單與最終 source SHA-256。
- `tools-manifest.json`：官方來源、官方 SHA-1 比對與本地 SHA-256；Media3 AAR 的 classes.jar 另記 hash。coroutines-core 1.9.0 共用官方 module SHA-256 核實。
- `compile-command.json`：完整 K2 命令，`-Xmx768m -XX:ActiveProcessorCount=2 -jvm-target 17`。
- `runtime-classpath.txt`：實際 runtime classpath；JUnit 命令為 JDK17 `java -Xmx512m -XX:ActiveProcessorCount=2 -cp <compiled classes>:<runtime classpath> org.junit.runner.JUnitCore dev.openeos.control.data.<test class>`。
- 首輪各組完整 log、2-failure baseline、修後 8-pass log、UTC start/end 收據。僅工作目錄正規化與行尾空白移除，原始 log hash 留在 manifest。

官方新增材料只來自 Maven Central `repo.maven.apache.org/maven2` 與 Google `dl.google.com/dl/android/maven2`。共用 JUnit4.13.2／Hamcrest1.3／Kotlin 工具先核來源 hash，沒有重複下載。Media3 extractor/common/container/decoder 1.8.1 為不同於原拒絕 UI 目標的正常官方依賴；未重試 media3-ui module/POM、未換代理／來源／TLS設定。

## Release Assessment

`none`：只修正測試 fixture origin 並新增驗證證據，沒有 production／版本／發布變更。iOS Apple-only build、Android 全 App 與真機門檻仍分開保留。

## 2026-10-20 後續 / Subsequent host verification

上述限制為 2026-10-18 當時狀態。[完整 Gradle host 驗證與交接證據](android-ui-host-closure-2026-10-18.zh-TW.md) 記錄 exact dev `bd9cf61365809c4ad14a1fa8f4469bb6def936fc` 的 [run 39360736976](https://github.com/js051/open-eos-control/actions/runs/39360736976)：原 1,867 unit tests 零失敗／零略過、debug APK 與 signer smoke 已通過，包含先前五類 UI host 閉包與三個負向 RTP 案例。未改原測試斷言，不重複加總早期 focused runs。

The complete Gradle host build/unit-suite gap is now closed for that exact head. This supersedes only the earlier host execution gap, not real codec decoding, instrumentation, GPU, physical-camera/USB/device evidence, PR `ci-complete`, or release acceptance. Prior failures and receipts remain preserved.
