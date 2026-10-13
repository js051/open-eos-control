# iOS 錄影指令 ACK 後狀態讀取失敗

## 範圍與來源

- 固定來源：`1842bdce957c050bee188aa579fd10bae365720b`，tree `c7e286e8f4529933cb34c7db654c8ea0964ef98c`。
- 修正 Desktop Bridge 明確回報 `RECORDING_STATUS_READBACK_FAILED`，以及 direct simulator 必要狀態讀取在 ACK 後發生的非取消錯誤。
- 不增加 wire 欄位、HTTP 請求、背景讀取循環或 REC 自動重送。Bridge 新 code 必須由伺服器在已收到相機 command ACK 後才產生。
- 此文件與測試使用合成相機資料，並非實體相機驗證。

## 已確認的程式邊界

1. DesktopBridgeClient 原先把 recording start/stop 的所有 HTTP error 一律向上拋出。App 保留前一份 snapshot，下一次按鍵可能再次 Start。
2. CCAPIClient 的 simulator 路徑在 REC command 成功後必須讀 `/ccapi/status`；這次讀取可能失敗。
3. Canonical CCAPI status 的一般 HTTP、網路與 JSON 可選讀取錯誤原本就會被 `firstJSON(required: false)` 吸收，不能把它們宣稱為可重現的一般 REC failure。測試將這些情況列為負控制。
4. Canonical status 中的 recording 是既有 client 快取的 ACK 方向，不是新增的獨立相機錄影觀察。本批不改變這個既有契約。
5. Direct `CancellationError` 與 `URLError.cancelled` 保留原傳播方式，不轉成 ACK typed error。Bridge 對 scoped response 的既有取消檢查也保留在解析 HTTP code 前。取消後跨層保留 ACK 證據的完整設計不在此批完成範圍；不可宣稱它已獲修復。

## 使用者行為

- 已 ACK 但狀態未知時，App 另外保存 session、command ID 與 Start/Stop 方向，保留原 snapshot。REC 指示仍依 snapshot 的 recording 值；不因 ACK 寫入推測的 true/false。
- 控制列提供英／繁中警示與明確 Stop 動作，維持 Video 模式；Photo 與既有需停止錄影的操作維持受限。Stop 沿用原本不受 Start 溫度／能力限制的路徑。
- Start 回傳明確 false 時顯示未確認開始警示，不保留永久 pending。回傳 null／缺失 recording 時保留 unknown，下一次明確操作只送 Stop。
- 警示列直接提供 Refresh。pending 時的 Refresh／事件更新只呼叫 status，保留既有 info／capabilities，不會重新限制 FPS 或重啟 Live View；REC 忙碌期間不新增 Refresh request。讀取必須來自同 session、相同 pending command，且取得非 nil recording 才解除 uncertainty。較早的 Refresh 不能清除後來 REC 建立的 pending；Start pending 讀回 false 仍顯示未確認開始警告。
- Stop ACK 後讀取失敗或 null 時保留原先已見媒體 ID 的 bounded video review。後續同 owner 只讀回報 false，才接續既有影片讀取。Start pending 讀回 false 不會被當成一次 Stop 或觸發影片檢視。
- 一般 command rejection、遺失 command response 或沒有精確 ACK code 的錯誤不會被偽裝成已 ACK。斷線或新 session 會退休原 pending；遲到的成功與錯誤不能寫入替代 session。

## 測試來源

`RecordingReadbackClientTests` 使用實際 DesktopBridgeClient／CCAPIClient 搭配受控 HTTP transport，覆蓋兩個方向的精確 Bridge code、required simulator ACK 後 HTTP／網路／JSON 失敗、command rejection／response lost、canonical 可選讀取錯誤負控制、取消傳播與只讀恢复不重送 REC。

`RecordingReadbackRecoveryTests` 使用相同 production clients 接入 CameraAppState，覆蓋 Start false/null、ACK error 保留 snapshot、Stop-only、Photo interlock、Stop 原 ID 邊界與新 MP4 選擇、讀回失敗／null、舊 Refresh、替代 session 的遲到成功／錯誤、caller cancellation、已取消 caller 零 REC，以及受限制時仍可 Stop。

既有錄影停止後影片預覽、原檔交付與英／繁中 UI journey 保留在同一 iOS 專案中，正常整批 iOS CI 應一併執行。實際 CI 畫面另確認三個輔助標籤使用插值 LocalizedStringKey 而顯示原始 key；本批改由 String helper 組合完整 key，並在同一 UI journey 斷言英／繁中重試與範圍說明全文。原本的 Share 斷言與 timeout 保留。

## 驗證狀態

- Swift core tests：`NOT_RUN`。本機無 Swift。
- iOS app tests／UI tests／Xcode build：`NOT_RUN`。本機無 Xcode／iOS 執行環境。
- 此批沒有本地受控紅綠測試結果，不可用 source 推論冒充測試通過。
- UTF-8、來源 blob／SHA-256、patch 可套用與 whitespace 檢查由隨批 manifest 記錄。
- 尚未推送，尚未觸發新 CI，未合併或發布。

## Release Assessment

預期 impact：`patch`。這是既有 REC 操作在 ACK／readback 分離後的錯誤恢復修正；不增加宣稱支援的相機。來源 app metadata 為 0.13.0，最新已發布 baseline 由完整交付流程重新核對。尚需獨立 source review 與 exact-head 正常 CI；實體裝置狀態仍未驗證。

## 尚未釐清的 Share UI 失敗與有限診斷

既有 REC UI journey 曾在原本 15 秒等待後找不到 Share。順序解碼的畫面顯示 viewer 保持存在；目前沒有足夠證據判定是按鍵、busy、取消、下載或檔案驗證的哪一階段。兩次原檔 GET 200 也可能來自播放器／fallback，不可直接當作手動下載成功。

僅此 REC UI case 在 Debug app launch 設定 `OEC_MEDIA_DOWNLOAD_TRACE=1`。診斷只保留最近 16 個封閉 stage：request、rejected-unsupported、rejected-busy、started、history、request-original、reply-original、validated、published、cancelled、failed。沒有檔名、URL、session、payload、錯誤文字或其他任意字串。未精確 opt in 時不發布診斷狀態，也不增加 accessibility value；Release 不編譯診斷型別、狀態、呼叫與 modifier。

既有 15 秒 Share 等待失敗後，UI test 只附上 close-preview 的診斷 value 和一張畫面，接著保留原失敗斷言；沒有重試、延長 timeout 或將失敗改成成功。新增實際 DesktopBridgeClient＋原 MP4 fixture 的 manual-download control，檢查下載中狀態、同一 ID、原 bytes、準備好之 URL 與 export payload；這不是觸控或 AVPlayer 的端到端驗證。

`@Published` 診斷會在 opt in 時增加 view 更新，可能改變事件時序。帶診斷的綠燈不能證明原本的根因已修復；此工具用來辨認停止位置，不承諾解釋或消除 Share 失敗。所有新增 Swift／UI 測試仍為 NOT_RUN。

## 2026-10-13 本批執行結果與觸控候選

精確 head `34a77cc6f6980967eb86dd7181ea17e1f37a9560` 的 CI `38357717267`：App 401/401 通過，UI 37/38；同一 REC journey 在 06:46:29 UTC 找不到 Share，原 15 秒等待失敗保留。上節 NOT_RUN 是提交前狀態，不覆蓋本次實際結果。

本輪 artifact `11794605452`（179,901,983 bytes；SHA-256 `e5624a2c3394f8507baaf10d679aef002e32df497f6df1a6d2d41bbd306e40a3`）中的 `recording-original-download-stages` 是實際零位元組 attachment。啟動 log 有診斷環境變數，使用 Debug app；下載入口在任何 guard 前都記錄 request，而失敗画面仍顯示 idle Download。這縮小到手動下載 action 未進入或其觀察接線；不能把播放器的 GET 200 當成此 action 已執行。

事件／AX 紀錄的 Download frame 為 17×17 pt，與原 label 的 48×48 layout 不同。點擊 (314.2, 90.2) 位於圖示青色箭桿，因此「點到空心中心」不符合畫面證據。最小候選為同位置 Download／Cancel／Share 三個按鈕 label 的既有 48×48 frame 明確設定矩形 content shape；不攔截整個播放器、不改按鍵座標、不增加重試或時間。REC journey 同時要求 Download 暴露至少 44×44 pt 觸控框，再沿用原單次 tap、Share 等待、原檔與相機命令斷言。

此觸控區域修正尚未執行 Swift／UI runtime。AX frame 狹小是實際觀察，尚不足以證明它是手勢未呼叫 action 的唯一原因；後續必須由同旅程結果與 stage trace 判定，不以 source review 宣告已修復。

## 後續 header 旅程結果與同群修正

`d4ba7965a6d4254f00d1f38f56c39723b0b66d11`／CI `38361569576` 的 App 401 項通過；原 REC journey 已於 07:27:23 UTC 通過 Download ≥44 pt、單次下載、Share 與原相機命令斷言。這證明該次 action 可達，不證明原 17 pt AX frame 是唯一根因。

同一次旅程接著在 Close→Back 失敗：07:27:23.698 Close tap 後，07:27:24.257 的 Back 被 XCTest 算出 (-1,-1) hit point，無法完成 scroll-to-visible，最終原 recorded-failure 檢查失敗。UI 38 項中同一 case 記錄兩項 failures，原 run 維持失敗。日誌不充分證明 Close 的唯一失效機制。

本輪完整群將同 header 的 Close／More 既有 48 pt label 加上矩形 content shape；REC journey 在原單次 Close 前要求互動可用與 ≥44 pt frame，Close 後要求 Back 互動可用再執原 tap。使用既有5秒互動 helper，不改 Share15秒、內容、命令或重拍斷言，不新增重試。frame 大小與中心 tap 不是邊緣 hit coverage，More 與實際整個 journey 仍需新 head UI 執行。此 source candidate 與 REC UNKNOWN 群整批驗證，不繼承上述 App 成功。
