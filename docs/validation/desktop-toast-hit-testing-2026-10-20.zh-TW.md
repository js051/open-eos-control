# Desktop 狀態通知的指標穿透驗證

## 已觀察的問題

- 基準 source：`28464dded03de02fe713f2308a06b060187924e2`。
- [Desktop dev validation 39472116853](https://github.com/js051/open-eos-control/actions/runs/39472116853)
  的原 Bulb／短快門恢復旅程，在 `CAPTURE_STATUS_READBACK_FAILED` 後，
  自動唯讀查找完成、手動「Check again」已 enabled 時失敗。
- 原五點 hit test 的中心、左右與上方皆命中按鈕，下方四分位點命中 toast。
  固定幾何為按鈕 `[967, 774.96875, 297, 48]`、toast `[840, 806, 420, 74]`，
  viewport `1280 × 900`。按鈕可見且 enabled，但不能完整接收點擊。
- 這是固定 `z-index: 100` 狀態通知攔截指標的產品問題。
  它不是 lint 失敗，也沒有證據將它歸因於本輪 CCAPI response-body deadline。

## 最小修正與不變邊界

`.toast` 是純文字 `div`，保留 `role="status"`、`aria-live="polite"`，
沒有按鈕、連結、tabindex 或指標事件處理。加入 `pointer-events: none`，
讓短暫通知不攔截下面原本可操作的控制。

`app.js` 與 `index.html` 不修改。因此通知文字、顏色、顯示時機、
成功 3200 ms／錯誤 6000 ms timer、切換語言時隱藏，
以及 Bulb／未確認快門恢復期間的既有 suppression 都保持不變。
沒有新增 Escape 或滑鼠關閉功能，也不提早隱藏錯誤來讓測試通過。

這只修正指標攔截，**不代表視覺上沒有重疊**。實際輔助科技朗讀、
各種放大與響應式排列的可讀性仍未經本批次驗收；若要求通知完全不覆蓋內容，
需要另一個有完整幾何驗證的排列修正，不能把本修正描述成該成果。

## 回歸設計

1. 從 production HTML 取得原 toast 與 retry 元素，載入 production CSS，
   在獨立測試頁重現已記錄的固定幾何。使用可見的 synthetic warning，
   不執行相機命令、不等待通知消失。
2. frozen baseline 必須在原五點 helper 失敗，且原因是下方命中 toast；
   candidate 必須保留可見文字與 live-region 屬性，五點都命中按鈕，
   並讓下方四分位點的一次點擊只增加唯讀 fixture 計數一次。
3. 原實際 Bridge + loopback HTTP peer 的 Bulb、短快門、ACK 後讀取失敗、
   JPEG review 與錄影恢復旅程全部保留。沒有變更原 helper、assertion、
   viewport、相機命令、retry 次數或等待預算。

## 驗證狀態

Source 候選與回歸已準備，JS syntax、15 項既有 static UI tests、
原完整 Ruff 0.17.1 指令與 `git diff --check` 通過。
移除新增的獨立 witness、提早建立 screenshot 目錄與 witness 呼叫後，
原 browser test 可逐位元重建；`app.js`、`index.html` 與基準亦完全一致。

本地 browser gate 在 setup 就停止：沿用既有 Node、Playwright 1.62.1、
Chromium 154.0.8037.57 與原 `CHROMIUM_PATH` 選項，Chromium 回報
`process_singleton_posix.cc:297 socket() failed: Operation not permitted (1)`，
隨後 SIGABRT；另有 crashpad database 初始化錯誤。
工具已執行命令，這不是 reviewer 拒絕，也不是預期的五點 baseline RED。
當前證據只能確定本地 runtime 的 OS 權限／能力阻礙，不能指定是哪個安全層，
更不能當成可繞過的授權。

該 gate 共 6.58 秒、取樣 process-tree RSS 峰值 113976 KiB（含 shared pages
在各 process 重複計算），未到達 witness 或原實際旅程。
因此 **baseline RED、candidate browser GREEN、原恢復旅程與本批次 CI 均未驗證**。
沒有變更 browser flags、權限、工具或執行路徑來繞過阻礙，也沒有下載或安裝。
CI 鎖定 Playwright 1.62.0，即使日後取得本地 browser 證據，仍只能視為補充，
不能宣稱工具版本完全相同。

修訂後的驗證策略保留上述原 CI 已觀察的失敗，將候選交由另行告知並授權的
既有 Desktop dev validation 驗證。它會執行新增 witness 與原完整旅程，
但不執行本地封存的 frozen baseline，因此不能補稱取得該 baseline RED。
目前 candidate CI GREEN 仍待證明；不修改本地限制或 CI 權限／工作流程。

本批次僅 bridge 與驗收文件，dev push 應觸發既有 Desktop／Windows validation，
不觸發 Apple／Android；不改 workflow，不使用 KVM，也不代表 `ci-complete`、
main acceptance、發布或實機相機驗收。
