# PC maintenance restore permit caller

原 G11/G13 的 maintenance keep-power cleaning preview restore；配合 Bridge additive restore permit 契約，非新增相機功能。

初次停止只有 non-poweroff sensor-cleaning 且原 preview active 時送 prepareRestore:true，成功且仍同 invocation 才保存合法字串 token。沒有 token 的舊 Bridge 不再收到 legacy restore Start，UI 提示保持監看停止並重新連線。原本 idle preview 的 controls/events 恢復不要求 token。

Start 前捕捉不可變 token，Start 回覆必須 echo token、started 與嚴格 active／warning。原 owner 的 cleanup/retry Stop 使用相同 token。只有該 token 的 stopped／cancelled-before-start receipt 可以結束舊 pending Start；receipt-only 不被當成目前相機狀態。新 warning 到達時保留責任，下一次明確 Stop 才做 no-token fresh cleanup。另一個 maintenance action 不繼承舊 token、revision 或 fallback。

Production-handler baseline 3/3 真紅；固定 liveview ownership 85/85，完整 modules 3860 passed、static UI 15/15、syntax/diff clean。獨立審查攔到並修掉第二次 maintenance 繼承舊 token、warning 晚到需多按一次 Stop 的候選缺口；相關測試使用完全有效 Start ACK，避免錯誤格式掩蓋 ownership 失敗。prepare 負向矩陣含 sleep、auto-power-off、原 preview inactive。

本文件目前是 host caller 證據。完整 Bridge API／provider ordering 驗證另見 server 同批文件；真正 Firefox 合成 UI 若執行另附，不推定 native Android／iOS、實際 CCAPI、物理 Stop 或清潔完成。原 strict Stop、錯誤優先序與 local-only DELETE 邊界保留。

## Firefox 合成旅程（2026-10-17 21:53–21:56 UTC）

實際雲端 Firefox 使用 production HTML/JS c080db51202760ba1a68381070f59a91a56c1b6b。以原生點擊完成監看 → 保持供電清潔 → 明確恢復；先取得 token 1，fresh read 後只用同 token Start，畫面恢復。第二次清潔送新的 prepareRestore:true 並取得 token 2。合成 transport 保留第二次 Start 回覆，原生 Cancel 後仍呈現 Retry stopping Live View；明確 Stop 送 token 2，取得 cancelled-before-start 收據，晚 Start 拒絕，畫面維持停止且未自動重啟。

[完整事件與三張畫面](evidence/maintenance-restore-token-firefox-20261017/) 保留 hash、source SHA 與 terminal 收據。fixture 在網路邊界回傳合成 JSON/canvas JPEG，不注入產品狀態或直接呼叫產品操作；只有本地證據 POST 使用實際 fetch。這驗證 caller 與原生 DOM 互動，不代替 Bridge production 排序測試、實際相機 Stop 或 native 三端驗收。Firefox PID 48862 已於21:56:39停止，server同時關閉。

實看另發現 maintenance history/action 說明被按鈕擠成狹窄文字欄，已列為下一個 G11/G13 版面修正；本批不將此畫面稱為完整視覺對齊。
