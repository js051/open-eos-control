# PC local video：延遲裝置列舉時仍可停止

產品 source：`4efb9a33901931fcddd0c87a4fe0f3246bbef0fa`，原列 #123（G07）。2026-10-17 16:53 UTC，在既有 cloud Firefox 157.0.1 / build 20261005135250、80% zoom 實際使用滑鼠操作 production HTML/JS。

測試頁僅向 loopback stdlib server 取用 production 靜態檔；注入合成 session/fetch 回覆，以及 canvas.captureStream 的 640×360 動態 frame。getUserMedia 後的 enumerateDevices promise 保持未完成，另設可見測試按鈕手動放行。沒有存取實體 camera、USB、UVC、HDMI 或外部服務。

1. 選擇 Local UVC / HDMI input 並按 Start。畫面顯示 SYNTHETIC FRAME，operation 為 Local video input started；enumerationPending=1，但 Stop video input 與輸入選單已可操作。
2. 保持列舉 unresolved 時按 Stop。畫面回到黑色待啟動畫面，operation 為 Local video input stopped；track stop=1、stream=false、videoHidden=true、Start 按鈕 enabled，pending 仍為 1。
3. 按 Release synthetic enumeration 放行晚回覆。pending 歸零，仍維持 stopped、stream=false；沒有重新變成 started 或重新啟用串流。

## 可核對證據

- [播放但列舉未回](evidence/local-preview-firefox-2026-10-17/started-pending.png)
- [停止但列舉未回](evidence/local-preview-firefox-2026-10-17/stopped-pending.png)
- [列舉晚回後仍停止](evidence/local-preview-firefox-2026-10-17/stopped-released.png)
- [狀態變更 receipt](evidence/local-preview-firefox-2026-10-17/events.jsonl)、[SHA-256](evidence/local-preview-firefox-2026-10-17/sha256.json)

receipt 是測試 fixture 的狀態觀測，搭配可見 UI 截圖，不代替實機 transport 證據。已在 16:54 UTC 關閉本輪 owned Firefox 與 loopback server，terminal receipt 確認退出。

## 未涵蓋

此增量只驗證既有 local preview 修正的 browser lifecycle。沒有驗證實體 UVC 權限、裝置拔插、驅動、HDMI 延遲、音訊、Windows/WSL、Android/iOS、真相機或完整 monitoring assists；不將原89列提升成全通過。初次 fixture engine 拼值修正後重新整理才開始上述有效流程，setup 失敗不算產品失敗或通過。
