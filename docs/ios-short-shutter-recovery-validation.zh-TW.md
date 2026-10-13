# iOS 短快門／AF 停止責任與拍攝 ACK 邊界

## 範圍與基準

本批從原 PR #236 的 `7e2f8ee1fd92a874a641b1140e85493644910f4b` 開始，補齊既有 manual still、短半按、AF start/stop 與 manual AF fallback 的失敗停止責任，沿用 Bulb 的 session-owned stop-only recovery。保持既有公開 API，不新增 held AF、相機 endpoint、盲目重拍或 camera reconnect replay。

這是本地 source 實作及待執行測試，不是 PR ready、main accepted、preview released 或真機驗證。未推送、未啟動 CI、未安裝工具、未變更 main／版本／安全設定。

## 原始缺口與反例

以下行號均屬上述原始 commit，而非修改後檔案：

- `ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIClient.swift:4160–4188`：`performGuaranteedRelease` 的 operation/release 錯誤只存在 stack-local 變數。manual still（1258）、半按（1320）、AF（1484）停止失敗後，原 `pendingBulbRelease` 沒有該命令。`shutterReleaseState`（608）、retry（1406）、close（582）及 write interlock（4778）因此不會保留短控制的停止責任。反例為一個 press 已送出、回應遺失、release 也遺失；原 API 仍回報 idle，explicit Retry 沒有可送的 release。
- `CCAPIClient.swift:1246–1249`：Simulator capture POST ACK 後，required `/ccapi/status` GET 的一般網路／HTTP 錯誤直接往外拋。`CameraAppState.swift:1048–1060` 只辨識 Bridge 的 acknowledged-readback 型別；Simulator 的 generic catch 會丟掉此次拍攝的 read-only review owner。
- **修正先前過廣的可達性描述**：canonical `.camera` 路徑的 `status()`（647 起）及 `loadShootingSettings()`（3483 起）透過 `firstJSON(required: false)` 讀取；`firstJSON`（4697 起）會吸收一般 HTTP、JSON 及 URL error。該路徑不構成「一般 GET 失敗必定拋 capture readback error」反例。`CancellationError` 會傳播，`URLError.cancelled` 在既有 optional read 中會被吸收；本批不改這個契約、不把取消說成新的 ACK 證據。
- `DesktopBridgeClient.swift:599` 原本只比對 `CAPTURE_STATUS_READBACK_FAILED` code，沒有 HTTP status gate。公開 backend `bridge/open_eos_bridge/ccapi.py:1741–1748` 只在 shutter 與必要 release 都 ACK 後以 **502** 回報此專用 code。401／403／409／500／503 的同名 code 不得被轉成已拍攝 ACK。

短控制停止責任的反例測試 source 先於 Core 修正建立，原碼的上述反例是 source 可證的缺口；由於此工作環境沒有 Swift／Xcode，沒有宣稱跑出 runtime red。

## 實作不變條件

- 每次短控制在送出 press 前保存原 client 的 method、endpoint、編碼後 release payload 與唯一 owner ID；client 的 origin 與 transport 固定。manual release 固定 `af: false`，專用 AF 固定 `action: stop`。
- 尚未 dispatch 的取消／Close 不取得 release 責任。已 dispatch 的 press、失聯或晚到 ACK 都必須等待原 press settled 後才補償 release；release task 不受 UI caller 取消影響。
- 必要 release 失敗仍是 `releaseRequired=true / releaseUnconfirmed=true / bulbExposureActive=nil`。新拍攝、AF、半按、Bulb、設定及 Live View start 維持 interlock；已有安全 stop 清理契約保留。
- Explicit Retry 僅送保存的 stop，不做 discovery、status GET、重新 press 或新相機轉送。重複 retry 共用 in-flight stop；一個 Retry 不會把該次 start compensation 的失敗偷偷再試一次。
- Retry 在第一個 await 前綁定原 owner ID；晚回應只能清除相同 owner。Close 取消並等待 owned short task，再做原 endpoint 的一次 final cleanup，退役後只保留 warning、不再持有可轉送的命令。
- Simulator 的讀取不能取代已有短控制 stop owner；actual dispatch 前再次檢查 pending owner，避免 read-only status 在 actor suspension 間採認既存 Bulb 後被短控制覆寫。closing／retired client 不採認晚到 status 的新責任，仍回傳退役後的 idle 或 unconfirmed warning；短控制不會永久取得 Bulb authority，後續獨立觀察到的既存 Bulb 仍可採認原 `/ccapi/bulb/stop`。
- Stop ACK 立即清除 release 責任，之後 status read 失敗不能重新建立責任。短 AF／半按的成功 stop 也不宣稱完成照片或 Bulb 曝光。
- Simulator 的 `CCAPIError.captureStatusReadbackFailed` 僅在 capture ACK 後 required status read 發生非取消錯誤，且 caller 本身未取消時產生。App 保留原 session／baseline 的 review owner，以既有 bounded read-only recent lookup／retry 檢查素材；不重拍、不因 ACK 假造新照片。
- App 的 capture／focus admission 相互排斥，避免重疊短控制把舊 callback 當新 owner；explicit Stop 仍走原專用入口。採用 Core release state 時，前後都驗證原 session generation，並比對 shutter-state observation revision，不能以過期 actor 回覆覆寫新的 stop／warning。成功短控制也採用當前 Core 狀態，以清除 status/event 曾觀察到的暫時 pending stop，必要時恢復 Live View。
- Bridge capture readback mapping 限定 502 與完整相同 code；其他 HTTP rejection 保持原 error。

## 測試 source 與驗證狀態

| 檔案／檢查 | 涵蓋內容 | 本地結果 |
| --- | --- | --- |
| `CCAPIShortControlRecoveryTests.swift` | manual POST／PUT、half press、專用 AF POST、manual AF fallback；start loss + stop loss、反覆失敗直到 ACK、精確 release body／路徑、write interlock、已確認拒絕、pre/post-dispatch cancellation、late ACK、close final cleanup／retired warning、replacement client 隔離、concurrent start／retry、下一個 owner、stop ACK 後 status cancellation、Simulator 外部 Bulb 觀察／短 owner 不被覆寫、late active status 不能恢復退役責任、成功正控制 | NOT_RUN：沒有 Swift |
| `CCAPICaptureReadbackTests.swift` | Simulator ACK→required GET failure→只讀 status；capture loss／rejection 不映射 ACK；兩種取消型別、caller 取消後晚到的一般錯誤、canonical optional-read 行為正控制 | NOT_RUN：沒有 Swift |
| `ShutterAutofocusTests.swift` | 既有 AF bool／method 行為及 Bridge 502 精確 code 正控制；401／403／409／500／503 同名 code 負例 | NOT_RUN：沒有 Swift |
| `URLSessionShutterWireTests.swift` | 既有真 loopback TCP peer＋生產 URLSession，manual POST／PUT 送出 press 後遺失回應、第一次 release 失敗；explicit retry 僅一個原 method／path／body 的 stop，不重播 press | NOT_RUN：沒有 Swift／Darwin |
| `CCAPIShutterRecoveryTests.swift` 及既有 Bulb／URLSession suites | 原有 Bulb 行為必須一同回歸；response gate 僅改為可在同 test target 重用 | NOT_RUN：沒有 Swift |
| `ShortShutterRecoveryStateTests.swift`、`CaptureReadbackRecoveryTests.swift`、`ControlResponseOwnershipTests.swift` | `CameraAppState` 真 session 呼叫、短控制失敗後 release-only UI 狀態、Simulator capture ACK 後只讀 review、caller 取消＋晚到一般 read error、capture／focus 互斥及 observation revision、retired session 不得覆寫新 session | NOT_RUN：沒有 Xcode／iOS Simulator |
| `git diff --check` | 工作樹 patch 空白／衝突標記檢查 | PASS（僅 source hygiene） |
| Swift／Xcode 工具確認 | `command -v swift`、`command -v xcodebuild`、`command -v xcodegen` | 均不存在；未安裝、未啟動或偽報 runtime 檢查 |
| 真實 iPhone／Canon 相機 | 實體 shutter／AF 停止與真正生成素材 | NOT_RUN |

新的 Core 競態測試重用 `ShutterRecoveryGate` 及既有 3 秒 bounded expectation 模式，沒有新增輪詢 sleeps、timeout 設定或 production test hook。原本短控制的 350 ms hold 保留。

具備既有工具的 macOS 驗證應先執行 `cd ios/OpenEOSCore && swift test`，再執行 repository 原有 iOS App/XcodeGen/Simulator 工作流。必須以最後完整批次 exact SHA 驗證，不能拿 source review 或舊 SHA 綠燈代替。

## Release assessment

這是既有控制與 recovery 的相容修正，影響分類為 `patch`；本批不改版、不提出立即發版。原 PR head 是本文件唯一 source baseline，最新 release baseline 未在本次本地工作重新查詢。Swift／App runtime、完整 exact-SHA CI 及真機證據均仍未取得，因此不得擴大成已驗證跨平台／實體相機能力聲明。
