# iOS 手動批次原檔：退休回覆與明確接續

## 範圍與狀態

- 基底提交：`806cbf2d3b4acaf81ed2f5861ec50c770f24b034`。
- 範圍：iOS 手動批次原檔的開始紀錄、逐項 admission、取消／換 session、歷史清除與使用者明確重綁未完成項。
- 程式修正與針對 production App／Store 呼叫路徑的 XCTest 已實作；本地沒有 Swift／Xcode，新增及修改原生測試狀態均為 **NOT_RUN**。
- 本文件不宣稱 PR ready、native test passed、相機實測、合併、發布或外部 provider 已保存。
- 不變更 shared contract、manifest schema、相機協定、版本、安全檢查或其他平台產品碼。

## 修正

1. `beginPlannedItem` 已持久寫入 `started`，但回覆到 MainActor 前 session 已退休時，普通手動批次與 continuation 都以捕捉的 ticket／receipt 結清舊列。不得把新 session 的相同 wire ID 或檔名當作舊列 identity。
2. `finishFailure` 仍允許已取消 task 寫入真正的 terminal receipt；`receiptIndex` 仍只接受同 epoch、同 batch、同 receipt 的 `started`，不能降格 `prepared` 或覆寫新 owner。
3. 完成最後原檔保存後仍重新檢查 task／owner／取消狀態。晚到完成不覆蓋使用者取消，已備妥原檔保留可交付；已驗證的暫存原檔和 `prepared` 副本仍保留。
4. 手動操作同步捕捉 clear generation，先取得 actor 的 history ticket，再以該 epoch admission 完整 roster。clear 早於 Task 啟動或發生於 admission 等待期間，不能重建已清除的舊歷史。
5. planned batch／item admission 在入口與原子 manifest rename 前檢查取消。`CancellationError`、`URLError.cancelled` 和 Store 的 `.cancelled` 都停止原檔 GET，不當作一般歷史不可用而降級繼續下載。
6. 舊 receipt 結清後可更新同一 installation-wide history；warning／refresh 受 clear generation 控制，不改新 batch 的 MEDIA ownership、結果、進度或 task。

## 保留的界線

- 普通手動批次在 owner 未退休時，使用者清除歷史後仍可完成已選原檔；不重建 roster／receipt。continuation 的 clear 則保持原有取消行為。
- prepared 原檔不會自動重 GET；接續先由使用者選歷史未完成列，再逐列明確綁定當前原檔並確認。檔名或 wire ID 相同不構成授權，也不自動映射。
- terminal manifest 寫入失敗時，不捏造 `cancelled`。同程序保持最後原子 manifest 的 `started` 並顯示 history warning，該列此時可能仍不可接續；冷重開 Store 才投影為 `unknown`，接續必須再由使用者確認不確定結果。這不是全部失敗皆可即時恢復的承諾。
- 已持久保存的 prepared 副本、歷史 receipt，以及仍由有效分享 payload／lease 持有的檔案，不因舊回覆結清而降格或刪除；一般 session reset 仍會釋放未被持有的暫存。明確清除歷史仍按既有確認流程處理。

## 新增／補強的原生反例（全部 NOT_RUN）

`MediaBatchDeliveryTests` 新增六個方法，並補強既有晚到成功／錯誤／進度矩陣：

- A 已 prepared available → B 的 durable admission reply 延遲（live started／cold unknown）→ C 尚未開始 → 重連 → B cancelled／C queued → 明確重綁 B/C 才接續；CCAPI 與 Bridge 都執行此測試設計。A 副本仍可讀，且只對 B/C 發新 GET。
- clear-before-task、history ticket reply、整批 admission reply、逐項 admission reply 四個邊界，不重建已清除歷史但保留既有手動下載語意。
- clear 後的新 owner 正在下載相同 wire ID／檔名時，舊 admission 回覆不能改新 manifest、結果、warning 或 busy。
- 舊 admission 成功結清及 terminal manifest 寫入失敗兩種分支都在新 owner running／busy 時放行回覆；A 保留、C 未開始。成功分支 B cancelled 且無 warning；失敗分支 B 不虛構完成，live started 與 cold unknown 清楚區分。
- 最後 prepared receipt 回覆期間，UI 取消與直接 Task 取消都保留 ready 原檔、prepared receipt，批次維持 cancelled。
- 三種取消型 Store 錯誤在整批或逐項 admission 時都零原檔 GET。
- 既有同 ID 新 owner 的晚到成功／錯誤／進度／defer 測試加入真實 Store；新 batch 正在 running 時放行舊回覆，並斷言舊列 cancelled、後列 queued、新列仍 started。

`MediaDeliveryStoreTests` 新增三個方法：

- clear 後舊 history ticket 無法 admission roster，manifest 不變；新 ticket 可正常 admission。
- planned batch／item 在進入前或 atomic replacement 前取消，manifest bytes 不變、沒有虛構 receipt 或殘留暫存。
- 晚到 terminal callback 不能降格 prepared／cancelled，也不能憑相同檔名寫到另一 batch 的 active receipt。

## 本地已執行的驗證

- Python 3.12.14 / Linux。
- `python -m unittest discover -s scripts/validation/tests -p 'test_*.py' -v`：100 項通過，包括既有 XcodeGen source-path 守門。
- `python -m unittest discover -s scripts/ci/tests -p 'test_*.py' -v`：46 項通過。測試輸出的「缺少 xcresult」文字為 exporter fixture 的預期輸出，不代表本次產生原生測試結果。
- `python scripts/release/verify-version.py`：版本一致性檢查通過。
- `git diff --check`：通過。

這些是 source／wiring／helper 檢查，沒有編譯或執行 Swift，不替代上述 XCTest。Swift、Xcode、iOS Simulator、實機相機及 Files provider 驗收均未執行；沒有安裝工具或觸發 CI。

## Release Assessment

- repo 版本基線為 `0.13.0`；本次未另外查詢遠端最新發布，也未修改版本。
- 建議影響：`patch`，修復既有手動批次取消／退休後未完成項被永久卡住及同群 admission 邊界。
- 阻擋：需要對最終提交執行既有 iOS 原生與 exact-head CI 驗收；本地守門通過不能升格為 PR ready。
- physical-device 狀態：pending。未測真相機、傳輸可靠度或 provider 落盤，不新增相容性承諾。
