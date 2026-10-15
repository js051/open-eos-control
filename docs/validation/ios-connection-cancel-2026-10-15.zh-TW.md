# iOS 連線中取消、修改設定與明確重試

## 範圍與交付狀態

本批以 `18e6be8169922fb95b14ec5cf0d9199763cc803b` 為來源基線，處理既有 iOS CCAPI／Desktop Bridge 連線流程。程式與 focused native 測試已撰寫；Swift 編譯、XCTest、UI、實機與 exact-head CI **NOT_RUN**。來源檢查不能替代原生執行或相機驗收。本批沒有 commit、push、CI、套件安裝、發版或權限變更。

不改錄影／快門／Live View 的安全確認規則，不新增自動重連，不更改既有六份待 KVM 驗收快照。

## 行為

- 連線中提供「取消連線」。取消同步撤銷該 attempt 的 UI 發布權與 busy 狀態，保留 URL、帳密、Bridge token 與已掃描選擇，可立即編輯。
- 修改欄位、取消、返回前景與舊工作完成都不會自行啟動連線。使用者必須再按連線／重試。
- 使用者明確重試時，如前次工作尚未結束，畫面顯示「等待前次連線清理」。必須等原本 opening 與定向清理結束，才建立新 client；等待中的新 attempt 仍可取消。
- 原本的 client、endpoint、驗證上下文與 session ID 保留到終態。晚到成功、錯誤與 finally 不得改寫新連線、busy、設定或 recovery。
- 每個 attempt 以自己的 retirement record 沿用同一 cleanup Task。pending dictionary 移除後，晚到 catch 也不能重新發出清理。
- close 前後的快門、錄影、Live View、維護警告合併保留；較晚的 idle 不會抹掉較早的未確認證據。late POST 或 late-ID DELETE 的三種安全未確認 code 另存為舊 client 的證據，合併至 previous-camera 專門警告；不建立 Stop URL、可重播指令或新相機的責任。
- Bridge session POST 與 DELETE 使用既有有界、禁止 redirect／驗證重播的 transport，不在失敗後退回普通 send。已 dispatch 的 POST 由獨立 task 觀察到有界終態，UI 取消不取消這份結果所有權；正常晚到 ID 可定向清理，不會因主動截斷 POST 回覆而製造 unknown。尚未 dispatch 即退役則不送 POST。本次禁止 redirect／驗證重播的保證只限 session create/delete；既有 health、metadata 等讀取仍走普通 send，不宣稱整段連線憑證絕不 redirect。
- 回傳 session ID 必須是 1–128 bytes 的 ASCII `[A-Za-z0-9_-]`，不得去除空白、轉型或從不完整回覆猜測 ID。DELETE 只接受完整空 body 的 HTTP 204。
- 遺失 POST 回覆、無有效 ID、不明錯誤或 DELETE 未確認時，保留獨立警告並阻擋新連線。改設定或取消不能消除阻擋；提示使用者先檢查原相機、重啟原 Bridge，再重開 App。
- pending cleanup 或 unknown cleanup 期間，UI 與直接呼叫都阻擋 Bridge scan，避免 discovery 與舊 provider open/close 重疊。設定仍可編輯；清理確認後不自動掃描或重連，需使用者明確操作。修改 Bridge URL/token 會清除舊選擇，需清理後重新掃描再重試。
- 「本機取消成功」「HTTP session 清理回覆」與「相機已實體停止／釋放」是不同證據；本批不聲稱實體確認。

## 已撰寫但未執行的 native 覆蓋

`ios/OpenEOSControl/Tests/ConnectionCancellationTests.swift`：10 個方法，使用 production `CameraAppState` 與 CCAPI／Bridge client，配合忽略取消的記憶體 transport gate：

1. 取消、保留欄位、修改 endpoint/auth、明確重試、等待原 DELETE、新 busy 與錯誤不被舊 finally 清除
2. 等待清理時再次取消，舊工作完成後不得偷偷建立新 session
3. POST 回覆遺失／DELETE 失敗都阻擋已排入及後續重試
4. health 尚未回覆時取消，不發出 POST 或 DELETE
5. 已分配 Bridge session 的 metadata 晚到成功／失敗，只清理原 ID，之後才准許新 attempt
6. 離線預覽與背景切換退役舊連線；返回前景不重連
7. 真正的新 attempt 失敗保留自己的 recovery
8. 英文／繁中取消與清理文字
9. late POST 與 late-ID DELETE 的快門／Live View／錄影 code 專門警告；確認警告不解除 unknown session gate
10. pending POST/DELETE 與 unknown 都不得執行 discovery；可編輯 endpoint/token，清理確認後需明確掃描與重試

`ios/OpenEOSCore/Tests/OpenEOSCoreTests/ConnectionCancellationTests.swift`：7 個方法：

- 逐項消費共用 49 個 session-creation classification vectors，包含安全未確認錯誤優先於一般 4xx
- late POST 成功後，以原 endpoint/token/ID 與未取消的清理上下文 DELETE；重複 close 不重發 DELETE
- 連線前 health 取消、CCAPI discovery／metadata 取消後不繼續後續讀取或 fallback
- 空白偽裝 code/engine、截斷 body、partial response 與不合格 ID 不能成為成功證據
- late POST 與 late-ID DELETE 的三種安全 code 保留在舊 client，不創造可重播 Stop 或向未知 ID 發指令
- 只有完整 HTTP 204 能確認 Bridge session DELETE；200/202/206/404/503 保持未確認

共用分類契約及 fixture：`contracts/connection-lifecycle/v1/`。fixture SHA256：`3856273dcab8e6620ad40dd912b4ba2380b3f0650ec6c8dbd3ae70ef739934bf`。

## 實際驗證結果

- 新增 connection source／fixture guards：11 項 **PASS_SOURCE**。這些只檢查原碼、fixture 與接線，不執行 Swift。
- 擴大來源檢查：97 項中 94 過、3 失敗，整體 **FAIL**，沒有改寫成通過。3 項是既有 LiveView/RTP source assertions；使用 `git show HEAD:<path>` 對乾淨基線逐項重現全部 3 項失敗。本批沒有修改這些 assertions 或其 RTP 實作。
- 既有失敗：`test_decode_only_output_precedes_optional_color_lut_metal_processing`、`test_renderer_evidence_does_not_claim_fresh_pixels_from_non_nil_readback`、`test_rtp_camera_ack_waits_for_actual_decoder_gate_before_source_is_active`。
- Capture configuration 既有來源 assertion 改為核對 `body["engine"] as? String → CreatedConnection.rawEngine → captureConfigurationSessionEngine` 的兩段精確傳遞，保留不可 trim／coerce 的原要求。
- `git diff --check`：PASS。
- `python -m pytest`：沒有 pytest 模組，該命令 exit 1，測試未開始。改用既有 Python 標準函式庫載入同一批來源函式／unittest 執行；未安裝任何套件。
- 實際 PATH probe 沒有 `swift`、`swiftc`、`xcodebuild`、`xcodegen`、`xcrun`。原生與 UI tests 皆 **NOT_RUN**，不是 PASS。真實 URLSession wire、iOS 背景／前景、實機／相機／USB 與完整 aggregate acceptance 仍需另行驗證。

## 後續驗收

在已授權、已有工具鏈的 macOS 環境，以最後整批 source／SHA 執行 Swift Core、iOS App、UI 與必要 aggregate 檢查。需要原生 UI 確認取消後的欄位可編輯、等待與阻擋訊息可讀、重複取消／連線、離線預覽與背景切換；需要實際相機驗證時，另列相機、傳輸、App build 與操作，不能用本 fixture 代替。
