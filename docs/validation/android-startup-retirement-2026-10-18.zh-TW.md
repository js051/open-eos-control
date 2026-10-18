# Android startup 取消與 Stop owner 修復

## 紅測試揭露的三個產品缺口

以完整 ViewModel／repository／CCAPI 原碼、真 OkHttp loopback socket 與官方 Robolectric 4.14.1 Android35 host 執行第一幀取消契約，準確阻塞 startup 的第一次 HTTP probe 後，原產品 8 案有 4 fail：

1. explicit restart 只取消 VM 的 frame-read owner，沒有退役尚在 backend 的 Start child，因此等在 transition mutex，不能在原 1 秒內開始 replacement。
2. startup 中 rapid off/on 的兩次 reconciliation 都可能在 connected 尚未發布時返回；舊 Start 退役後沒有重新排入最新 on 意圖。
3. disconnect 取消 enclosing CONNECT 時，await 先返回 CancellationException，wrapper 與 backend child 的 NonCancellable cleanup 同時送出 Stop。實際 trace 在任何 ACK 前出現兩個 Stop POST。
4. mandatory release 案的舊 running=true 斷言不符合本地 readiness 退役契約；安全責任必須保留 StopRequired=true、release 沒被取消且 ACK 前沒有 Stop。

## 修正

- restart 在等 transition mutex 前退役 pending Start，replacement 仍經原 interlock、Stop debt 與 mutex。
- CameraSession 明確記錄 liveViewStartRetired，僅 Start 正常返回「已退役」時設置；publish 後重新 reconciliation。普通 Start 失敗、failed Stop 不走此路徑。
- LiveViewStartOperation cancellation 先等待原 child 完成，再判定 wrapper 是否仍需 Stop。保存 child 實際 Throwable；非取消失敗原樣傳出，不能把未清 debt 當作自動重送許可。純取消且 backend 未自行 cleanup、已完成 Start 在 await 前被退役等情形仍保留必要 wrapper Stop。

純 owner 測試原 16 案有 2 fail，候選 16/16 passed，包含 lazy Start 執行前取消、完成但 await 未恢復、child failed cleanup 與 parent cancel 競態、retired wrapper Stop 失敗，以及 Bridge-style 純取消。組合產品候選的真 socket／Robolectric 8/8 passed；整合後 107 個 data/test 檔與 48 個 UI/helper/test 檔完整編譯；09:45:46–09:47:52 共 186 個不同案例通過（75 owner／network／Robolectric、106 caller、5 recovery／focus gate），來源 hash 核對一致。[完整證據](evidence/android-startup-retirement-2026-10-18/)。

另嘗試 CameraViewModelPreviewTest 的完整閉包，確認仍缺同檔 Compose gallery 元件相依材料；未抽取 production helper 或替換 stub，保留 unresolved canPreviewGalleryItem 的閉包診斷，未計入成功數。

## 測試前提修復與界線

原測試使用只有 FFD8FFD9 的 marker-only payload，並假設第二次 HTTP 是 CONNECT 首幀；目前 backend 會快取已驗 framing 的 startup frame，VM 首次 fetch 消費 cache，第二次 HTTP 已屬 periodic polling。測試改阻塞 startup 第一個 probe；capture 案以 JDK ImageIO 產生的合成有效 JPEG 建立 ready session、明確消費 cache，再排 capture，確保阻塞的是 release 後的 auxiliary refresh。

本批原測試類使用 Robolectric runner 與 sdk35／manifest NONE，並增加官方 testImplementation。這是可解 BitmapFactory 邊界的 host harness，與舊純 JUnit 條件不同；不是 emulator／App完整Gradle／USB或真相機驗收。原始 8 fail、有效JPEG仍7 fail、truthful startup4 fail、兩修後2 fail及最後8過依序保存，不隱藏過渡結果。

1 秒／3 秒測試門檻、產品 HTTP deadline、no replay、mandatory release 均未放寬。join 保證等待原 producer 真正結束；若底層不合作，不能單靠 join 宣稱 bounded cancellation，仍依 backend 原有 deadline 與取消行為。

## Release Assessment

`none`：修復既有 startup 取消責任，不建立 tag 或 release。
