# Android ViewModel 完整來源與 caller 閉包恢復

2026-10-18，沿原功能契約恢復官方 Compose 1.7.6、Lifecycle 2.8.7、annotation 1.6.0 與 Kotlin Compose compiler 2.2.21 的最小 host 編譯閉包。Compose 版本以原 BOM 2024.12.01 核對；官方下載與共用材料均保留 checksum 收據。

## 確證修正

- 原 CameraViewModel 完整檔編譯有 5 個 unresolved reference，皆來自缺少 MaintenanceStopEvidence、MaintenanceRestoreEvidence、maintenanceMayRetryStop、maintenanceRestoreStopSafe 的 import。本批僅補這四個 import，不替換或抽取 production 函式。相同材料下原檔紅、修後完整 48 個來源檔編譯成功。
- CameraRecordingReviewRecoveryTest 的合成 media listing handler 在 503 早退之後才計數，導致失敗請求已實際發送但計數不增。將原 countListing 移至早退之前，保留原 +4 次 read、無重送 Stop、新照片不被當作錄影片等斷言。原 38 項有 1 fail，候選 38/38 通過。

## 執行範圍

完整 caller 包含 connection configuration、recording review、capture review、shutter recovery、event recovery 五類共 106 個測試。原 baseline 105 過／1 fail 已保存，09:37:00–09:38:41 修後完整 106/106 通過，結果見同批證據；不將 focused 重跑重複加總。

[證據](evidence/android-viewmodel-caller-2026-10-18/) 保存編譯紅／綠 log、完整命令、來源 hash、原失敗、各 caller 執行 log 與官方材料收據。R 由原資源及 SDK35／aapt2 static-link 產生，僅用來提供原符號，不代表 App 打包或資源畫面驗收。

## 未完成界線

這是完整 ViewModel／repository caller 的 JVM host 切片，並非 Android App build、USB／真相機或 Compose 畫面驗收。第一幀取消另有獨立原 8 fail：原 marker-only JPEG 不能作為有效 Bitmap，且 startup first-frame cache 已使舊第二次 HTTP 阻塞階段失效；既有官方 Robolectric 的修正 fixture 仍在分開驗證，不把這批 caller 成功視為 decoder 或第一幀取消已完成。

## Release Assessment

`none`：補既有來源 import 與合成測試計數，不建立版本、tag 或 release。
