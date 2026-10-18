# Android Live View fallback／錄影未知結果修正

## 產品修正與實際證據

來源基準 `773ecf58f3b30e700a77b64e8d17b711e8e539b5`。2026-10-18 08:55–09:01，106 個完整 Kotlin 原始檔編譯成功；31 類原 JUnit 共 500 項，444 passed／56 failed。失敗完整保留，未宣稱全套通過。

1. AUTO Live View 某來源失敗後，成功 Stop 會 retire 舊 geometry；下一個來源沒有建立新 owner，JPEG fallback 因而失敗。在 pending Stop 已清空的 admission 點 reset geometry。未確認 Stop 仍禁止新來源；舊回呼 revision 仍失效。原 25 項由 5 fail 變成 25 pass，包含 cleanup unknown、禁止重複 Stop、禁止不安全 fallback。
2. 已送出 REC 的成功 HTTP 回應若不是合法 JSON，strict parser 拋 IllegalStateException，舊分類只認 IOException／JSONException，漏掉未知錄影結果。改依 wire evidence `UNKNOWN` 分類非取消例外。NOT_SENT、REJECTED、RESPONSE_RECEIVED 不降級，CancellationException 原樣傳出，無重送。相關 23 項真 socket 案例通過，含取消／admission／拒絕／ACK 後 readback 負例。
3. 修正舊測試 fixture：Bridge session DELETE 精確回 empty 204；lifecycle session 提供一致的 session/camera engine；focus-info Live View Start 提供並消耗必要第一 JPEG。保留原業務斷言與嚴格 production receipt 規則。

09:08:22 完整 106 檔重新編譯，09:09:08 最終整合 9 類 **61/61 passed**，執行前後 source SHA-256 一致。另原 LiveViewGeometryState 10 項全部通過，包含舊 read／mutation 在 reset 後不能覆寫新 geometry（該次 33 項整合候選的其他 3 個失敗亦保留）。不能把重跑算成額外不同案例。

## 尚未收斂的原始測試

兩個大型網路測試類用虛擬 runTest 時鐘包真 socket；隔離 real-clock 候選（60 秒外層 guard，產品 deadline 不變）後 CcapiClientTest 為 178/183、DesktopBridgeClientTest 為 36/40。候選尚未入版。剩餘失敗涉及 strict HTTP rejection 舊 exception 斷言、still cancellation socket fixture、maintenance restore token fixture，仍須獨立核對；不以這些候選結果覆蓋原 500 項 baseline。

## 可重現性與邊界

[證據](evidence/android-liveview-recording-recovery-2026-10-18/) 保存 31 類 baseline、完整 source manifests、K2 command/classpath、候選紅綠與最終 61 項 log、UTC 收據及原始／正規化 SHA-256。沿用前批官方依賴來源收據。只把工作目錄正規化、移除行尾空白。

此為原 Kotlin production、原 JUnit、真 loopback OkHttp socket 的 host 執行。沒有 Android OS／USB hardware／decoder／Compose／真相機執行；完整 Gradle App、Apple-only build、exact-SHA CI、三端真機驗收仍未完成。

## Release Assessment

`patch`：修正既有 Live View AUTO fallback 與錄影未知結果分類，沒有新 provider 或功能擴張；尚不建立 tag／release，不宣稱 PR ready。
