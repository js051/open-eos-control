# PC 錄影停止後影片檢視與原檔交付

## 原功能群與行為

接續 P1 拍攝到素材檢視流程，基底為 PR #237 對焦歸屬修正 d254960ec7f269cb74bf5e8a0ef06dfe84494933。Android 已有錄影停止後影片查找，本批補 PC 對應流程，與 iOS 同群分平台驗證。

使用者明確停止錄影前，保存本連線已看過的素材 ID。只有 STOP 回傳 recording=false，才啟動既有有界唯讀清單查找；新影片須未在原快照中，照片和已見影片不會冒充本次候選。開始錄影不查素材；停止狀態缺失、仍為錄影或錯誤不宣稱停止成功、不自動重送 REC。查找保留原四次清單讀取及等待上限；未就緒或讀失敗可由使用者明確再查，不再拍攝或錄影。

英／繁中影片狀態明說「新可見」不能證明由本次錄影產生。成功沿用既有影片 viewer、metadata 與單檔原檔交付，沒有新增下載、播放或儲存框架。換連線後的舊 STOP 回覆不得覆寫新 status/busy；新照片及影片操作互相退休舊查找。

## 受控測試

production 函式的 Node 模組新增16個控制，原35個全部保留，總51個通過。包含 current STOP、新影片／新照片／舊影片、唯讀重試與 REC 次數、缺失／null／true 停止狀態、錯誤、無 MEDIA_BROWSER、busy、載入範圍之外已見 ID、照片／影片互相退休，以及重用 session ID 的新物件隔離。基準未修來源的兩個正向查找案例實際失敗，修正後51項通過。這是受控軟體證據，不是相機或 browser runtime。

完整 Canon-shaped browser 旅程以既有合成服務和真 H.264 fixture 驗英／繁中 Start/Stop、尚未出現、排除照片、唯讀再查、新影片 viewer 及原檔 bytes/hash。服務的 STOP 本身不產生影片；測試明確注入稍後出現的檔案，不宣稱拍攝因果。正常 CI 執行前，browser runtime 狀態為 NOT_RUN。

## 保留前輪失敗與 fixture 修正

focus head d254960e 的 CI38349518327 終態 FAILURE。secret、JVM/APK及 Windows bundle 成功；desktop browser 在既有 unsupported-directory case 中，Details dialog 開啟後立刻斷言 Download enabled，當下為 disabled。失敗 artifact11790331963 SHA256712c103f50ccb2f6f800e50bfa8576390d34761014be262942c9302f7ed02724 已驗並查看 PNG；metadata 請求尚在完成的時間差是來源支持的假說，不把晚截圖當唯一因果證明。

本批讓該案例在原預設 timeout 內等待 Download enabled，再保留相同 assertion；不改產品 loading 行為。失敗 cleanup 原本未關 Details dialog，導致它攔截 Disconnect 並污染後續情境；加入正常 Close 操作，原 failures 仍完整收集，絕不把 cleanup 成功轉為案例 PASS。原正常 CI 必須驗新 exact head，不重跑舊 head。

## Release Assessment 與界線

發布基準 v0.12.0 Development Preview。本批補齊既有錄影到交付能力，屬 minor 功能群的一部分。真相機錄影寫檔時序、光學／聲音／編碼品質、外部目的地耐久保存及三平台 combined runtime 仍未驗；App 集中人工驗收依使用者指示後置。未合併 main 或發布。
