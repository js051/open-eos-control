# Bridge CCAPI Bulb 停止責任與 PC 恢復流程

基準：PR #194 head `5a042d24724ca2f4d2b5dc2988d2e9ef99711c6f`。此批是既有 Bulb 的可靠性修復，未更動正常 AF／拍攝決策、版號或發布狀態。

## 問題與修正

原 Bridge 只有在 full_press 成功回應後才保存 active。相機已收到開始但回應遺失，加上補償 release 失敗時，後續 stop 不再送 release，另一個 start 卻可再次送 full_press；DELETE 也可能錯報成功。

現在發送開始前保存原 session 的精確 method/path；開始／停止不明時保留責任，對外以 additive `shutterReleaseUnconfirmed=true` 與 `bulbExposureActive=null` 表示，不把未知偽裝成曝光 active。固定錯誤碼沿用原 envelope。除權威讀取、安全 stop 與 disconnect 外，實作中的相機寫入均 fail closed；重試只發 release，成功確認才解鎖。

只有已確認開始且已確認停止才記為觀測到 Bulb 完整操作。成功補償未確認的開始不算拍攝成功。release ACK 已到但後續 status 失敗，不會恢復 server-side 停止責任。

PC 提供持續停止重試，已知 active 與未知停止都不受目前模式／能力撤回影響；普通舊 status 不會清除本機風險。只有串接在同一 Stop 後、同 session 的明確 fresh readback 才可確認停止。晚回應或晚失敗不得改到替換 session。

斷線仍做最後一次原 release，失敗完成本機清理後回傳風險，不假稱可對已移除 session 重試。相機註冊保留至 close 結束，阻止釋放尚在進行時替代 session 取得控制。PC 另保留上一連線警告，直到使用者確認已檢查機身；此確認不送命令，也不把舊 release 交給新相機。

## 獨立驗證

- 以獨立 Python stdlib HTTP peer 接 production Urllib transport 和 Bridge FastAPI；沒有匯入產品 Simulator 來重複同一假設。
- 在原產品碼上實際得到 4 個紅測，分別涉及狀態／錯誤責任、漏送停止、重新 full_press 與錯誤 DELETE 成功。
- 另以紅測確證舊 recovery GET 失敗污染新 session，以及 delete 提前釋放相機註冊兩個競爭，再修正。
- 最終 16 個 HTTP/API cases 通過，含 20 個應阻擋的 mutation routes、POST／PUT 原方法、截斷／遺失開始與停止回應、成功補償、反覆停止失敗、ACK 後 status 失敗、observed evidence、舊 schema 預設 false、close_all 不中斷其他清理及替代 session 隔離。
- 5 個既有／新增 static UI checks 通過；完整 JS module command 通過。新增 module 直接執行 production PC control functions，測 6 個模式／能力／未知停止／fresh readback／舊欄位缺失／新 session 契約。這比 browser 窄，不能當作畫面已驗收。
- JS syntax、Python compileall、git diff --check 通過。

## 尚待精確 CI 的部分

本機既有 Chromium 無法建立 IPC socket，受允許的正常執行仍失敗；雲端瀏覽器也阻擋此 loopback 位址。沒有繞過限制。pytest、Ruff、PyAV 不在此環境，未另外安裝。

self-contained 真 browser 回歸已接入既有 `npm run test:browser`，涵蓋真 PC UI → Bridge → HTTP peer、停止重試、mode change、status 失敗、斷線警告與新 session，並輸出桌面／窄螢幕截圖。完整 Ruff／pytest／browser、視覺檢查及精確 head ci-complete 須由正常 CI 閉合；此文件提交時尚未算通過。沒有實體 EOS／手機或光學證據。

## 相容性與未完成項

- 新 boolean 有 default false，既有 status 欄位與 error envelope 保持相容；不宣稱其他 engine 也有同等停止責任。
- Android／iOS Bridge adapters 目前忽略新增 field，需要後續獨立恢復 UI。舊 client 的不安全寫入仍由 server 擋住，但不能說其完整操作流程已完成。
- gphoto2／EDSDK、iOS direct CCAPI、一般 AF hold，以及整體 status cache ordering 另列缺口。
- 既有 PC isBulbMode 優先採 cached capabilities value；browser fixture 對機身模式變更等待兩次完整 refresh，不把此未修的相鄰 cache 問題冒稱已解決。

Release Assessment：live baseline v0.10.0 Development Preview，建議 patch；不合併、不改版號或發布。PR ready 仍須最終精確 head 的 CI 與必要 UI 證據。


## 精確 CI 後的視覺追查

初次 head `e3c2217642093a7ed093f40edc9576b2d9869005` 的 [CI 37195713746](https://github.com/js051/open-eos-control/actions/runs/37195713746) 已完整通過，包含 Ruff、pytest、真 browser、Windows bundle、JVM／APK 與 ci-complete。browser artifact 的 SHA256 為 `d5b0f4f98e683004f8eae0948bf355684a9c0a6212c54f8dc857735ddc2bcaca`。

下載並目視後仍發現：窄螢幕長 error toast 遮住 Stop；中央 Start 雖已 disabled，但暗青色仍像可用；fixed 的上一連線提示覆蓋曝光列。另以獨立 production function 紅測證明 Start handler 缺少相同責任 guard，雖然 server 最終會拒絕。不能只憑 CI 綠燈就把這個使用流程算完。

增量修正保留持續警告與操作區錯誤，不再疊 toast；中央 disabled Start 使用明確灰色，Camera／Local Start handler 與可用狀態保持一致。上一連線 alert 改在連線卡或快門操作區的正常文件流，保留確認按鈕，不擋新相機操作、不送任何跨 session 命令。

本地 API 16 例、static UI 6 例、完整 JS module、syntax／diff check 通過。新增真 browser 斷言要求窄螢幕 Stop 完整在 viewport 內、五點 hit-test 無遮擋且可實際點擊；兩個 Start 一致 disabled；舊提示存在時新 session 的 Stop 與確認鈕仍可達，且提示不與曝光列重疊。增量的新精確 head CI 與四張截圖 QA 尚待執行，初次 e3c2217 結果不冒充這次修改的驗證。
