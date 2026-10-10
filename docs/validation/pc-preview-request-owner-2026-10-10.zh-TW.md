# PC 顯示預覽請求的取消歸屬

## 範圍與基準

基準為 accepted main `b2eac1e65b58ec318c5c786ba93841f3812dfa15`。本批只管理 PC 瀏覽器對 Bridge 的 image `/preview` GET；不改原檔保存、影片播放配置 POST、相機命令或伺服器取消協定。

原 PC 程式已有 generation 判斷，會忽略關閉／替換後晚到的成功及失敗；它也沒有因顯示預覽而取得全域 busy latch。因此本批不把全域錯誤污染或 UI busy 當成已重現的 PC 問題。可證的缺口是 GET 沒有 AbortSignal，畫面失效後瀏覽器仍可繼續接收不再需要的圖片。

## 行為契約

- 每次 image 預覽持有自己的 AbortController。關閉、切換、手動重新整理及 session reset 先使 generation 失效，再取消該請求。
- 舊請求不配合取消而稍後成功或失敗時，原 generation 保護仍阻止發布；舊 finally 不得清除較新請求的 controller。
- 背景 contents refresh 保留使用者目前的預覽及其請求。圖片已取得但 decode 尚未完成時，關閉仍阻止晚到 decode 顯示。
- 影片 POST 配置的回應包含唯一 ticket URL。保留接收晚到回應並 DELETE 的既有責任；不盲目 abort POST 而遺失清理 token。
- 原檔保存有獨立 owner／controller，關閉顯示預覽不取消它。

## 驗證狀態

基準新增測試為 39 項：27 個 controls 通過，12 個失敗均為 image GET 未取得 AbortSignal。涵蓋關閉、手動刷新、替換、重用素材 ID、重新連線與重用 session／素材 ID，並分別交付晚到成功及失敗。原 18 項背景刷新／預覽案例保留，另有過期非 AbortError、影片 ticket 清理及原檔 owner controls。

修正後再補舊 video finally、decode 清理一次、目前傳輸錯誤及原檔取消獨立性 controls，最終 43/43 通過。完整 15 個 module scripts 通過（2.72 秒，最大 RSS 63,596 KiB），Node syntax 與 diff 檢查亦通過。此為本地 production-function 測試，不是瀏覽器 runtime。

production 與新增 browser 案例各由獨立 source review 檢查，均無 blocking finding。新增 browser 案例保留真 Bridge 回應，以被動 observer 要求 native fetch 在釋放舊回應前因關閉而拒絕為 AbortError；其後必須能重新解碼同一張 160×120 圖片，且不新增相機寫入。route 清理錯誤仍失敗，原 12 秒 timeout 與全部原旅程保留。

本機 browser runtime 未執行，exact-head 正常 CI 尚待完成；本文件不預先宣稱 PR ready、main accepted 或裝置 runtime 通過。

## 限制

Bridge `/preview` 是同步 route；CCAPI／gphoto2 的底層讀取仍可能持有 session lock。瀏覽器取消不證明相機傳輸停止，也不證明其他相機操作立即可用。本批不新增重試、不調整 timeout、不改 server lock 或安全設定。

這是合成軟體驗證範圍，不是實體相機相容性。PR219 原 Compose 根因 UNKNOWN、普通 Android App 的 KVM 驗收阻礙及 frozen v0.13 HOLD 均不能由本批解除。
