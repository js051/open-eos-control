# Android 相簿範圍與單張下載協調

## 範圍

以 `v0.10.0` 的 `1164d3610ddb1da702b024acb87ea75da5f3dcbe` 為基準，修正既有 Android 相簿／下載流程的兩個缺口。沒有改變相機型號儲存位置、原檔格式、Camera Import 契約、PC／iOS 或相機控制指令。

同一交付包另含已授權的控制可靠性改善；完整變更與最終驗證請見[整合報告](android-offline-reliability-2026-10-03.zh-TW.md)。本文件只描述以下兩項相簿問題。

## 缺口與修正

1. 最近項目完成載入後，若在下載期間切換「整張卡」，原先程式會先把 scope 改成 ALL，再因 MEDIA busy 跳過真正載入。舊結果與 COMPLETE 狀態仍保留，可能把最近 60 筆誤標為完整卡片。現在 UI 停用範圍切換，ViewModel 也拒絕忙碌期間的範圍變更；操作結束後可重新選擇並載入。
2. 單張 RAW 或「另存至其他資料夾」使用的 document-destination 下載入口，原先沒有像批次下載一樣取消未完成的卡片遍歷。現在單張與批次入口都在共用 MEDIA 操作入口取消並等待 listing 結束，沿用 generation guard 使晚到結果失效，避免列舉與下載競爭。

## 回歸驗證

- JVM：在 MEDIA 操作執行中要求 ALL，原 scope、項目、hasMore 與完成狀態不得改變；操作完成後可切換。
- Compose：兩個範圍按鈕在傳輸期間停用，點擊不呼叫切換；傳輸結束後恢復。
- ViewModel／HTTP／ContentResolver 整合：使用合成 fixture、延遲 listing 及 App 自有測試 content URI，驗證單張入口取消 listing、原檔寫入與晚到結果隔離；此測試不代表系統 SAF picker 或跨 App URI grant 驗收。
- 本輪最初相簿修正已通過 514 項 JVM 測試、lint（0 errors／57 warnings）及 App／instrumentation APK 建置；其後整合控制可靠性變更，最終結果與重試紀錄以[整合驗證報告](android-offline-reliability-2026-10-03.zh-TW.md)為準，不沿用先前 513／124 項歷史數字。

## 限制與交付狀態

沒有連接或操作實體相機。R6 Mark III 大卡片／弱網、光學對焦、其他手機及 Camera Connect 版本儲存路徑仍待相應實機證據。修正不提供跨程序續傳或 RAW 顯影。

使用者追加授權以繁中 Conventional Commits 拆分並推修正分支；不合併、變更版號或發布。新增測試與本地檢查不等於 exact-head CI 或 `main-accepted`，確切分支狀態另以交付摘要與 GitHub checks 為準。

## Release Assessment

- Baseline：`v0.10.0` Development Preview。
- Impact：`patch`，修復既有範圍標示正確性及單張下載競爭。
- 使用者價值：避免將最近項目誤認為全卡，並使單張與批次下載的列舉取消行為一致。
- 交付門檻：本輪 Android 驗證見整合報告；PR／exact-head CI 與 main acceptance 為不同階段，本文件不構成合併或發版核准。
- 實體裝置：本輪未測；不聲稱已解決使用者回報的非預期對焦。
