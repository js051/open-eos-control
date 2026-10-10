# Android HTTP 圖片預覽的取消歸屬

基準：accepted main `dbe8c791e263f41c8b8ee43a313707b89f0b11bc`。PC 圖片 GET 的取消已在 #228 接受；本批不以 PC 證據代替 Android 驗收。

## 已界定的問題與修正

Android viewer 關閉原本只清 UI；其 MEDIA 工作仍可阻擋後續媒體操作，同 session 的晚到 IOException 仍可由通用錯誤處理發布。CCAPI 已有 coroutine 驅動的 Call.cancel；Desktop Bridge 圖片 helper 則只在 body read 之間檢查取消，無法中斷等待 headers 或阻塞讀取。

- 只有 CCAPI_NETWORK／DESKTOP_BRIDGE 的非影片顯示讀取建立自己的 request identity／Job。先建立 identity，再發布 viewer；Close／離開 MEDIA 在註冊前發生也會保留取消責任。
- 已撤銷／替換／換 session 的 request 不得啟動。MEDIA pending 發布後發生取消時，先安裝精確 owner completion，再取消 lazy Job。
- MEDIA busy 保留至真正完成；取消發生在 coroutine body 開始前也有 terminal cleanup，不能留下 busy，亦不能清除較新的 metadata／preview 工作。
- 非合作讀取晚到的成功／IOException 先重新確認 coroutine cancellation。只改 display read 的處理，不變更快門、對焦或其他安全例外。
- Bridge 圖片 HTTP call 使用既有模式的 cancellation watcher，涵蓋 headers、image body 及 error body，保留 response closing、session／大小／MIME 驗證及正常錯誤。

## 測試與 review 狀態

新增 `CameraMediaPreviewOwnershipTest` 13 個方法 × 兩種 HTTP transport，共 26 個 parameterized executions：真 HTTP 取消與同素材重開、lazy dispatch 前取消、取消清理仍 busy、晚到錯誤、disconnect/reconnect、reentrant metadata successor、初次 viewer 與 pending MEDIA 發布時的 Close／離開、運行中的 mode exit，以及 USB／video controls。

新增 `DesktopBridgeMediaImageCancellationTest` 11 個方法：headers／部分 body／error body stalls 的 Call.cancel，正常 HTTP error、bytes／MIME／auth／能力證據、8／32 MiB 上限、未知長度超限、無效圖片及回應 headers 前的 session 替換。每個 fixture 保留精確請求數，不增加重試。

所有新增 Kotlin 測試目前 **NOT RUN／尚未編譯**。官方 build-tools34 已核官方 checksum 恢復，但一次本地 Gradle probe 在 28.78 秒以 exit1 結束：正常 repository sources 無法解析釘版 AGP8.6.1 plugin，未進測試。未更換鏡像、關閉 TLS、改版本或變更安全設定。由正常 exact-head CI 執行完整適用 gate，不能把 source review 當 runtime pass。

獨立 source review 找到並修正先發布 UI、稍後才建立 owner 的 admission race；修後 review 無新增 blocking finding。雙語 README 保留版本檢查契約，明確區分 main 的未發布0.13來源與目前實際已發布的 v0.12.0。版本檢查與 whitespace 檢查通過。

## 明確界線

- USB/PTP 圖片 transaction 與影片 stream／ticket 配置不套用 HTTP image 取消。USB control 只驗 ViewModel transport policy，不是 transaction drain 或真機證據。
- 取消 Android→Bridge HTTP 不保證 Bridge→camera 同步 I/O 停止；不宣稱 server lock 立即釋放。
- 原檔保存保留獨立 owner；未新增 save-specific 測試，既有完整保存 suite 必須仍通過。metadata successor control 不被冒充原檔保存驗收。
- 不提供圖片解碼、實體相機／手機／USB 相容性證据。PR219 根因 UNKNOWN、PR223 KVM 驗收阻礙與 frozen v0.13 release HOLD 保留。
- 本批為 patch-level 修正；尚無新 PR ready／main accepted／發版結論。
