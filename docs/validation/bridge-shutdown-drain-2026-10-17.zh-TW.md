# G12 Bridge 關閉流程：一般清理錯誤不可跳過其他 session

基線：`540768bc9dfc1798a6a790a7f71c3be691692aef`。範圍是原三端功能對帳 G12／116（Desktop Bridge HTTP service）的程序關閉流程；不是新的相機能力、原生驗收或發布。

## 可重現反例與修正

`SessionManager.close_all()` 先清空 session registry，再逐一呼叫 `session.close()`，原先僅捕捉 `BridgeError`。第一個 session 若回報一般 `OSError`、provider `RuntimeError` 或 `subprocess.TimeoutExpired`，迴圈立即退出，後面的 session 完全沒有收到 close。

這不是僅依賴不可能的 mock 型別：production `GPhoto2Session.close()` → `_stop_movie_stream()` → `GPhotoMjpegSession.close()` → `SubprocessByteStream.close()`／`_finish_process()` 的 terminate、kill 與第二次 wait 能傳出一般 OS／timeout 例外，並無統一轉成 `BridgeError`。回歸直接執行 production `SubprocessByteStream` cleanup，以合成 process 方法製造 terminate 錯誤及 kill 後仍 timeout，確認後面的真 `CcapiSession.close()` 仍執行其合成 transport close。沒有啟動子程序、網路相機或實體 release。

產品差異僅擴大 close loop 的捕捉範圍為 `Exception`：先嘗試每個 session，再重新拋出第一個原始例外物件，保留既有 first-failure reporting。`KeyboardInterrupt`、`SystemExit`、`asyncio.CancelledError` 這類 `BaseException` 保持立即退出，不宣稱 process interruption 也能完成所有清理。

同步檢查 `app.py` lifespan 之前的 `media_listings.close()` 與 `playback_cache.clear()`：前者只設停止 event；後者取消標準 `threading.Timer`，對檔案移除已抑制 `FileNotFoundError`／`OSError`。本輪未找到相同的可證明一般清理例外阻擋，故未改動 lifespan 順序或加入推測性的整體 refactor。

## 本地證據

- 新 `bridge/tests/test_session_shutdown.py` 共 11 案。未改產品時 **7 fail／4 pass**，失敗原因為後續 close 未執行，或後來的 OSError 取代先前 BridgeError。
- 修正後新 11 案，以及既有 main／standalone／build-standalone 測試共 **37 pass**；另跑既有 `BulbShutdownTests` **2 pass**。兩組不重疊，共 39 個 host cases，非全專案測試或功能完成率。
- 覆蓋第一個原始錯誤保留、多個錯誤後仍關閉末端 session、正常／重複 shutdown、三類 process interruption、production 子程序 cleanup、後續 production CCAPI session close 與真正 ASGI lifespan exit。
- 使用既有 EOS pytest 8.4.2 環境，`PYTHONPATH` 明確指向本分支 `bridge`；沒有安裝或下載。宿主 FastAPI／Starlette 顯示既有 TestClient/httpx deprecation warning，測試沒有因此跳過。
- 修改檔 Ruff 與 `git diff --check` 通過。沒有重跑已綠的完整模組矩陣；沒有 browser、Windows、Android、iOS 或實體相機驗收。

## 本輪 G12／G13 剩餘範圍

- **116／G12**：本修正只補普通例外造成的 session 清理飢餓。強殺程序、永不返回的 provider close，以及相機是否實際停止仍無證據；不把 host close 呼叫成功當作物理停止。
- **117／G12**：`bridge/scripts/build_standalone.py` 仍要求 Windows，使用 PyInstaller 收集程式與靜態資源；本輪只執行既有 builder／launcher host 測試，沒有產生或操作此候選的 Windows exe，不能宣稱 installer／WSL／Windows 權限流程已驗。
- **122／G13**：發現另一個尚未修正的可重現 recovery 缺口。`static/app.js` 的 `refreshHealth()` 設定 `healthAttempt` 後等待 `api("/health")`，`api()` 的 fetch 沒有 health deadline；只有 finally 才解除 attempt。以既有 `connection-guidance.test.js` production-function harness 讓該 promise 保持 pending，第二次呼叫被 guard 拒絕，結果為 requests=1、healthAttempt=true、healthRetryDisabled=true、healthFailed=false、status=checkingBridge。Bridge／HTTP 回覆若一直未結束，使用者無法用「再次檢查 Bridge」恢復；本關閉流程修正沒有處理此初始化／錯誤恢復問題。
- **126／G12**：`edsdk_loader.py` 載入 separately-installed provider entry point；`edsdk_contract.py`／`edsdk.py` 只有中立契約與邊界驗證。真 Canon EDSDK binding 仍是原帳本的 D/E，不因 shutdown 測試變成已實作。
- **128／G13**：iOS UI／可達性不是本次 PC／Bridge source audit 範圍，未操作 Xcode 或裝置；不得把本輪 host 證據延伸成 iOS 驗收。

## Release assessment

- 本地 `CHANGELOG.md` 最新記錄與 source version 為 Development Preview **0.13.0**；本次離線工作未重新查詢遠端最新 release，發布基線仍須交付端核實。
- 影響：**patch**，修復既有 Bridge 關閉流程的 backward-compatible 錯誤，不新增功能、不改 wire contract 或版本號。
- 狀態：本地 implemented。尚未推送；exact-SHA CI、PR ready、main acceptance、發布與產物均未在本分支執行。實體相機／多相機／Windows／原生平台仍未驗證。
- 非目標：不修改個別 backend close 行為、停止責任、權限、憑證、provider 安裝、G13 health recovery 或其他平台功能；不以此文件授權發布。
