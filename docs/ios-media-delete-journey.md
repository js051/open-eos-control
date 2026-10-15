# iOS 精確來源永久刪除流程

## 範圍與現況

2026-10-15：在既有 iOS CCAPI 與 Desktop Bridge 路徑，實作單筆及已選批次刪除的確認、單次 logical dispatch、取消剩餘派送、逐項結果與舊連線歷史。沒有新增 USB、EDSDK provider、整卡搜尋、自動解除保護、自動 retry 或刪除補償。所有新增測試使用合成資料。

本地僅 source/fixture integrity 驗證已執行。Swift 編譯、Core/App XCTest、URLSession 真實 loopback、SwiftUI/XCUITest、真實相機驗證均 **NOT_RUN**。工作環境沒有 Swift、Xcode 或 XcodeGen，不以文字檢查替代這些驗收。

## 操作契約

1. 每次永久刪除先開新的檢閱清單，凍結 opening owner/generation、原始 UTF-8 ID、名稱、來源、資料夾資訊及當次 selection token。排序、filter 或之後選取不能改動該授權清單。使用者可直接取消檢閱，零 DELETE。
2. 重複精確 ID 保留第一次；最多 1,000 列，超限拒整批、不截斷。隱藏、未載入、已知保護、不可寫入、缺能力及來源未知都有明確提示。缺少 protection/writable 是未知資格，不改為 false，也不宣稱已確認安全。
3. Bridge 回傳的已確認 session engine 與精確 media ID 一起判定來源：ccapi 為相機來源；libgphoto2 的 gphoto2: 為相機卡、gphoto2-host: 為已連線桌面之拍攝檔儲存區。檢閱保留實際 Bridge endpoint；未知 provider 不猜測來源。直接 CCAPI 不接受 host 類型作相機資料。
4. 每列 dispatch attempt 只可進入一次。已知 protected/writable veto 在 App 與 client 邊界均擋下。新刪除必須重新檢閱，沒有 automatic unprotect、重送或「補償」DELETE。
5. 取消停止尚未派送的列；已派送的一列等待終態，不宣稱可撤回。已取得完整 confirmed ACK 不因取消降級。斷線會退役 owner、取消工作、保存當時證據；晚回應只可用原 run／row／attempt 身分補記該唯一 invocation 的有效收據，不能污染新 owner、續派剩餘列或復活已清除的歷史。
6. 成功只消費原 attempt 對應 selection token 的精確 ID。取消選取後又重新選取同 ID，會取得新 token，不會被舊結果清掉。刪除來源不連帶刪除已下載／已交付的副本。

## 結果

- confirmed：此 invocation 得到完整、空 body、framing 合法的 204。
- rejected：完整有效回應符合明確拒絕 allowlist：400/401/403/404/405/409/412/413/415/422/429。
- uncertain：已進入 dispatch 但沒有終態證據，包括其他 2xx、3xx、408、499、5xx、截斷／無回應。停止剩餘派送。
- not-sent：已開始該列但 transport 邊界未進入。停止剩餘派送。
- not-started：沒有開始的剩餘列，保留取消／退役／前項未知原因。
- skipped：資格、來源或載入條件不符合，沒有派送。

Bridge 自有同步 route 的明確契約是 204。CCAPI 在本 repo 只以 204 fixture 支持確認；這**不是**所有 Canon 型號、韌體或協定版本只有 204 才合法的宣稱。真機合法碼須有獨立證據後才可擴充。

## HTTP 與恢復界線

專用 URLSession DELETE streamed task 拒絕 redirect、拒絕第二份 body stream 及 authentication challenge replay；一般 GET、metadata、upload transport 不被改寫。原始 Authorization 只隨原 request 送出，預設 TLS trust 保留。`URLSessionMediaDeleteTests` 的 loopback gate 要驗證 307/308、lost ACK、503、truncated body 下 server 只收到一次 exact-target DELETE；**尚未在 Apple runtime 執行，不能聲稱 wire exactly-once 已驗證**。

每次 App 執行最多保存 20 次／2,000 列結果，跨重新連線保留，並非跨 App 重啟的永久稽核記錄。超過上限前自動淘汰最舊且非執行中的完整批次，不能截斷單批收據；淘汰或手動清除皆使舊檢閱失效，不能重新啟動舊授權。被清除／淘汰的批次不因晚收據復活。舊 owner 歷史在離線連線頁仍可查看，不會對替換 session 發命令。

「重新讀取目前來源清單」只讀取同 owner 的目前 scope，不自動擴大成整卡掃描。結果欄位不會被刷新改寫：近期、filter、部分載入看不到某列，不能證明先前刪除成功。重連後應由使用者明確定位原來源，再另開新的刪除確認。

## 驗收對照

- `MediaDeleteJourneyTests.testAll528SharedPolicyVectorsUseProductionDecisions`：216 eligibility、24 admission、32 outcome、192 HTTP evidence、54 selection、10 roster。
- `testAll12BatchVectorsThroughActualAppAndBothHTTPClients`：12 個 shared batch cases 各過 Bridge/CCAPI，App executor 與實際 clients，檢查逐列結果、每 ID dispatch count、成功 selection 消費。
- 同檔其他測試：canonical-equivalent Unicode IDs、混合 camera/host 來源、新 selection token、手動讀取不升格、unknown/protected/read-only/unloaded skip、cancel/retire 及有界歷史。
- `testRetiredReceiptBeforeResponseAndAfterClientRecordCannotMutateReplacementOwner`：兩種 client × 退役發生於回覆前／client 已 record 後 × confirmed/rejected/lost ACK；新 owner 重用 exact IDs，舊 receipt 只更新自身歷史，不清新 selection、不移除新清單、不釋放新 busy lock；已清除／淘汰歷史不復活，busy admission 不能移動 active batch。原生未執行。
- `MediaDeleteClientTests`：實際 clients 嚴格 204/framing、refusal allowlist、同 attempt 不重送、preflight 不送、取消不能降級已有 ACK。
- `URLSessionMediaDeleteTests`：Apple loopback no-redirect/no-replay wire gate，未執行。
- `testExactDeletionReviewCancelMixedSourcesLostACKAndRetiredHistoryInBothLanguages`：英文／繁中真 SwiftUI 檢閱、取消、混合來源、lost ACK、斷線後舊歷史；DEBUG synthetic peer 不刪任何實體檔案，未執行。
- 舊 ownership、Unicode sibling、capture viewer tests 保留；fixture 的媒體 DELETE 改回既有 server 明定的空 body 204。原本直接刪未載入項的 test 現要求先 skip，再明確讀回該精確項目後才建立新操作。
- Python source guards：靜態 call-path、owner/token、專用 transport、localization/resource 與 fixture identity。這些不執行 Swift。

## Release Assessment

- Latest release baseline：v0.12.0 Development Preview（協調任務 2026-10-15 03:54 UTC 核定）。
- Source version：0.13.0，尚未發版，本次未改版本。
- Impact：minor；iOS 新增使用者可操作的已選批次刪除能力。
- Delivery state：source implemented / native validation pending；不是 PR ready、main accepted 或 preview released。
- Blockers：Swift/Xcode native 編譯與測試、精確 SHA CI，以及 Apple wire no-replay 與 SwiftUI 實際互動驗收未執行。
- Physical device status：NOT_RUN。若測真機，另以明確授權建立的可丟棄素材，驗證 supported camera/firmware/transport 的 ACK、protected/refusal、卡／資料夾 ID 與斷線行為。不得以合成 fixture 宣稱實機資料安全驗收。
