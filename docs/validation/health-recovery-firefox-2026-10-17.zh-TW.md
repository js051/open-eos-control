# PC 初始化／Bridge health 恢復的 Firefox 證據

受驗產品 source：`90aa16db1f35bb993d1df66a6b72adc886f7cbc9`；App JS SHA-256 `ff8da4363dbb930c7246ee3001658301902a31548f46a0e4f6f67839267bbb7f`。2026-10-17 17:33–17:34 UTC，在既有 cloud Firefox 157.0.1 / build 20261005135250、80% zoom，使用 EOS 自己的合成測試 profile 操作。對應原列 #122/#123、G13/G07。

## 受控依賴與實際結果

Production HTML/JS 保持原檔。loopback server 注入[公開的合成 fixture](evidence/health-recovery-firefox-2026-10-17/fixture.js)：optional enumerateDevices 永不 resolve；第一次 /health 也永不 resolve，收到 abort 只計數而故意不結束；第二次 /health 立即回成功；可見按鈕讓第一次舊回覆晚到，且刻意與新回覆不同，設為 authRequired=true。沒有連真 Bridge、相機、USB、provider 或外部服務。

1. 沒有按任何重試前，telemetry 已記到 healthCalls=1／Checking bridge，證明 pending 的可選裝置列舉沒有擋住自動 health。第一張保存圖是在15秒deadline後：明確 timeout 提示、Check Bridge again 已可按，abort=1，沒有自動產生第二個health請求。
2. 實際點選 Check Bridge again，healthCalls=2；畫面顯示 libgphoto2 ready，timeout錯誤消失，token仍為 Optional on loopback，Scan可操作。沒有宣稱此時已自動掃描或連上相機。
3. 實際點選 Release stale health response，第一次回覆晚到；仍保持新結果、Optional on loopback與無錯誤。authRequired=true 的舊回覆沒有覆蓋第二次結果。

## 證據

- [逾時後可重試](evidence/health-recovery-firefox-2026-10-17/health-timeout.png)
- [明確重試成功](evidence/health-recovery-firefox-2026-10-17/health-retried.png)
- [舊回覆返回後仍保留新結果](evidence/health-recovery-firefox-2026-10-17/health-stale-released.png)
- [狀態變更 receipt](evidence/health-recovery-firefox-2026-10-17/events.jsonl)、[檔案 SHA-256](evidence/health-recovery-firefox-2026-10-17/sha256.json)

17:34:27 UTC，本輪 owned Firefox 與 loopback server 已退出，terminal receipt confirmed。Desktop handoff完成後沒有繼續保留測試服務。

## 界線

這是瀏覽器真 UI 操作與production JS，依賴為合成；不是實體UVC／HDMI、相機連線、Windows打包、Android或iOS驗收。實際斷網、TLS、proxy、DNS及native OS picker未測。受驗head中的mobile source變動不因這組PC旅程變成native通過。未改版本、main、PR或發布狀態。
