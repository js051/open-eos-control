# Android 連線設定歸屬與恢復流程

日期：2026-10-05 UTC。基準：已發布 Development Preview 0.11.0，main `3af7df40c3f998835eb755d55701f85177714ed8`。本分支為 `feat/android-connection-recovery`，獨立於尚未發布的素材日期、評分與下載紀錄分支；不跨分支加總測試，也不以程式存在代表 PR ready。

## 使用者流程與原因

原連線表單在等待掃描或初始化時仍可編輯，但網址、驗證資料與目標修改不會取消舊工作，也不改變其 generation。Bridge 掃描會把 A 的清單寫回已顯示 B 位址的畫面；直接 CCAPI 連線也可能讓網址 B 顯示 A 的相機 session。

`CameraConnectionConfigurationRecoveryTest` 以真 ViewModel／Repository／HTTP peer 將 A 的回應暫停，在修改設定後放行原回應。基準三項為 2 failure／1 pass：上述兩個污染案例失敗，明確 Disconnect 的控制組通過。最小修正後，相同三項 3/3 通過；另驗證 B 的實際請求、身分、電池狀態與只讀 GET。這是可控協定因果證據，不是新相機硬體或對焦問題的根因。

- 接受網址、帳密、Bridge token、相機選擇或連線目標的修改時，沿用既有取消、generation 隔離與 teardown。新連線先等舊責任清理完畢；編輯本身不自動連線、掃描或送相機命令。
- 未連線時真正 pending 的 CONNECT／BRIDGE 有固定可達的取消入口。一般 safety interlock 不冒充等待中的連線，因此快門 release 風險不會出現無效的取消按鈕。
- Bridge 只有成功完成且清單為空時顯示「已回應但找不到相機」；初次尚未掃描、取消、修改來源與失敗不冒稱相機為空。
- 明確失敗後，以實際 HTTP status／transport 型別提供英繁中指引。401／403 說明拒絕存取並請檢查驗證與允許存取的設定，不斷言密碼錯誤；5xx 不被伺服器 body 裡的「401」字樣誤分類。
- 直接 CCAPI 拒絕驗證會展開帳密欄位。修改後須自行點連線；Dismiss 只清訊息，不重試。未知錯誤仍明列未知，不根據型號猜契約。
- 無可用 Wi-Fi route 在 HTTP 前就以具型別錯誤回報，保留可操作的同網路指引；沒有變更網路選擇或系統安全設定。
- USB 連線頁說明找不到裝置、非 Canon、沒有 PTP 或缺少授權的差異；只對 Canon PTP 候選提供直連授權入口。這不擴大相機相容性，既有 Debug 診斷保留。

## 資料、相機命令與安全邊界

新增恢復 state 只有 target 與 reason enum，不保存網址、token、相機識別或原始 response body。一般連線／掃描失敗不再直接把原始診斷寫到 UI／log。快門、AF、Live View release 安全例外保留原優先處理與既有詳細診斷；本批不宣稱全面清除所有診斷文字。

CCAPI 仍走原 `GET /ccapi`、`GET /ccapi/` 與既有 identity fallback；Bridge 仍用 `GET /health`、`GET /v1/cameras`。只保留其已取得的型別／HTTP 證據，不增加探測、重試、fallback 或曝光命令。可成功的 identity fallback 仍優先完成連線，不因前次端點失敗而封鎖。

URL preflight 保留 http／https 與既有 base path，不改寫協定。Bridge 原有禁止 URL 內帳密、query、fragment 的規則提早驗證，錯誤不回顯輸入。TLS 檢查維持原行為，沒有忽略憑證、安裝 CA 或改系統權限。

## 驗證狀態

- 最小所有權修正：基準 3 項 2 紅／1 綠，修正後相同 3 項全部通過。
- 完整恢復 focused JVM：30/30 通過，含 9 項 production VM／HTTP、13 項實際協定回應、4 項 typed failure mapper、4 項輸入與安全訊息 state 契約。
- 初次 focused run 為 22 項／1 failure；測試誤以為 coroutine 恢復後第一層 cause 必為 Bridge exception。改查有界 cause chain，保留最外層安全例外、實際 HTTP status 與不新增請求的斷言。另 4 項 mapper 因首次選錯 class 名稱未執行，已於 30 項 run 實際補跑。
- 新增 8 項 instrumentation：6 項 state／Compose 版面與操作、2 項真 OpenEosControlApp→ViewModel→native CCAPI HTTP。包含驗證拒絕→自動展開→明確修改／重試，以及 Cancel 在舊回應仍未放行時完成，再明確建立新 session。這些不是 simulator shortcut 或物理相機驗證。
- 第一次 aggregate 的 androidTest 編譯抓到錯誤 import 與誤對 derived connected 使用 copy；並記錄到建置中有文案／測試變更，該 run 不列為最終同樹驗證。修正後再次凍結來源。第二次 aggregate 已產生679項全通過XML，但在Lint階段Gradle daemon意外消失（原因未證實），故不以該run宣稱成功；同一來源改單worker收斂，保留所有gate。

最終單 worker 檢查於 08:27:23–08:30:08 UTC 以 terminal exit 0 完成，2m44s，79 tasks（17 executed／62 up-to-date）。App JVM 679/679（60 suites，0 failure/error/skip）與前次已完成測試的XML來源完全一致；收斂 run 的 test task 為 UP-TO-DATE，不冒稱又重新執行679項。Lint 0 error／52 warning／2 information，App及androidTest APK成功，8個新裝置案例均已編譯。

- 最終來源 manifest SHA-256：`d07bd5445bfd78bafefa5a2fe022795957480d253c08685e5ddefebe35a70078`，前後一致，且與daemon中斷run相同。
- App APK SHA-256：`ef6219bfdfffa23d9521a8956fd64d90c5ef7ebe7c0dc3ec2ce894bb73a7ad8e`。
- androidTest APK SHA-256：`491b942d7ef31c932560ff418dabe6bbf79938a7f7c2c4a1cfae90639442a7b9`。

裝置 runtime與精確head CI仍待結果。實體相機光學、真正手機網路與 USB 權限／硬體相容性仍無新證據。

## 發版評估

Release Assessment：相較 0.11.0，設定污染修正屬 `patch`；完整取消與失敗恢復入口屬新增使用者流程，整批建議 `minor` Development Preview。沒有改版本或發版；完整 CI、main acceptance、版本 PR 與 immutable candidate 依既有規則，各自完成後才可晉級。

不包含自動重連、背景連線／自動傳圖、額外 Canon 命令、預覽 Close 取消修正、PR #202 實驗，或把其他本地分支能力當成本分支已存在。
