# PC 自動原件 JPEG 驗證與失敗恢復

狀態：本地 implemented；尚未推送、未執行此變更的遠端 CI、未合併或發布。
本記錄不改寫先前已封存的 cohort 收據。

## 範圍與協定

- 僅前景自動拍攝匯入，保留兩次完整 baseline、舊 ID 排除、精確 fresh metadata、穩定大小、owner/cancel 與零快門重送。
- 原有 exact original GET 加 `attempt=single&validation=jpeg-v1`；不新增相機 GET、替代路徑或自動重試。手動 JPEG/RAW 與舊 `attempt=single` 不加此限制。
- 先驗 HTTP 程式庫實際提供的 Content-Type（含重複欄位），不把檔名推測 MIME 當證據。缺欄可繼續；存在但空白、空值、控制字元、非 ASCII、非法參數、非允許類型或重複欄位拒絕。
- 原件只進既有私有 spool，下載中最多 64 MiB；關閉後比對寫入 SHA-256 與讀回 SHA-256，再依共用契約解析，執行真正的 PyAV/FFmpeg MJPEG 解碼。解碼程序再比對其讀回 digest。
- 成功才加 `X-Open-EOS-Original-Validation: jpeg-v1`；瀏覽器在第一個寫入與 close 之前要求精確 proof。拒絕回應沒有正向驗證標頭。
- digest 是本地位元連續性證據，沒有相機提供的密碼學身分保證；原件不重編碼。

## 邊界及資源

共用契約：64 MiB、64,000,000 pixels、單軸 16,384、4,096 markers、256 scans、4,096 ASCII MIME 字元；保守 8-bit SOF0/SOF1/SOF2、1/3/4 components，完整 SOF/SOS、表、progressive sequence、entropy stuffing/restart、精確 EOI。

Native 解碼為單一 spawned process、單執行緒、lowres=3，最大輸出軸 2,048。15 秒 deadline 包含啟動時間；逾時 kill，最多同步等候 2 秒回收。極端 OS 回收延遲留給單一背景 reaper，保留容量閘，防止繼續堆積程序。這不是 Windows/Linux 完整記憶體 sandbox，也不宣稱能立即取消 opaque native call。停止、換 session 或背景化仍以原 owner/cancel 閘拒絕發布。

## 失敗恢復

原件不合法、驗證逾時、讀寫或 close 失敗均停止當輪。清理僅限 owned pending/spool，保留先前已交付檔案與既有 transfer history；既有 history 儲存失敗警告不被隱藏。重新啟用是明確使用者操作，重新選資料夾、建立兩次新 baseline，不補抓失敗或已交付的舊 ID，也不重送快門。

## 驗證分類

- Python：共享固定向量、真正 strict native decode、原件 route、HTTP 重複欄位、單 GET、零回退、closed-copy digest、逾時/exception/start-failure 程序回收、spool 清理、手動相容。
- Node owner：缺／錯 proof 在零寫入零 close 時拒絕、abort、失敗紀錄、不自動重試、顯式重新啟用、兩次 baseline 排除失敗與已完成 ID、只交付後來的新 JPEG。
- Browser E2E 已加入 proof 移除與真 backend entropy 截斷／恢復案例，但本環境無 Chromium，狀態 NOT_RUN。DOM layout、瀏覽器 File System Access 與此新流程的端到端驗收不可由 Node 測試替代。
- Linux PyAV native decode 已執行；Windows frozen EXE、macOS、實機相機均 NOT_RUN。打包檢查涵蓋 wheel 與 frozen entrypoint 的 freeze_support 接線，不視為實際 Windows 執行。
- 有效 progressive、CMYK、restart、EXIF 等固定原件能解碼；四個早期 entropy EOI 樣本會被 strict PyAV 拒絕，即使 Pillow load 接受。有限 corpus 不代表所有損毀都能被拒絕。

## Release Assessment

本地套件版本 0.13.0 未變；此已存在自動匯入流程的相容修正建議 impact 為 patch。已核實發布 baseline 為 [v0.12.0 Development Preview](https://github.com/js051/open-eos-control/releases/tag/v0.12.0)（2026-10-15 03:54:34 UTC）；遠端 exact-SHA CI 及合併／發布仍需整體交付驗收。實機與平台驗收仍按上述 NOT_RUN 保留，不宣稱 PR ready 或相機可靠性通過。

## 2026-10-15 UTC 本地結果

- 最終固定契約 111 向量，SHA-256 `639e85317c0d5fbb72f4f5f8ebccfa696593c7c41881f43900021ccd30f1f6d1`。
- 原件 focused pytest：229 passed；Bridge 全套：815 passed（66.95 秒）；simulator 全套：62 passed；契約 integrity：7 passed。
- Bridge 與 simulator Ruff 全過；36 個 Node module scripts 全過，Node reporter 合計 1,184 tests，另有兩組自訂 runner 明確回報 40＋20 tests（共 1,244 項明確計數，不把向量迴圈另加）；所有 production JS 與新增 browser test syntax 全過。
- `--wheel --no-isolation` 建置成功；從 wheel 匯入的 spawned native probe：11 有效固定原件通過、4 個 entropy 提前 EOI 拒絕。
- 瀏覽器、Windows frozen 執行與實機狀態仍為 NOT_RUN，不因以上本地通過而變更。
