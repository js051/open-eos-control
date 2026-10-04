# Android 單次機身事件的狀態恢復

## 問題與基準

基準為 PR #194 的 `5a042d24724ca2f4d2b5dc2988d2e9ef99711c6f`。機身事件只是變更提示，Android 必須再讀取權威 status 與 capabilities，不能直接把 event payload 當作完整狀態。

原 event loop 先消耗提示，再讀 status／capabilities。若其中一次 GET 暫時失敗，catch 只延遲後改讀下一個 event；下一個是空事件時，前次提示已遺失。結果可能是機身已改錄影狀態或曝光能力，UI 卻保留舊值，直到另一個事件或手動 Refresh 才恢復。不是每次網路錯誤都發生，條件是已消耗的通知後續權威讀取失敗。

## 修正與恢復契約

- event loop 自己保存不可變 changed-key 快照；權威 status 與 capabilities 都成功發布前，不再消耗下一筆 event。
- 讀取失敗使用既有 1／2／5 秒上限退避；成功 poll 本身不會重設 snapshot 恢復的失敗次數，計數也有上限。
- 沿用連線世代、camera-state revision、active-operation 保護；新命令穿過讀取期間會使舊 snapshot 失效。沒有任何 capture／REC／setting 寫入重試。
- 成功發布控制狀態後才處理保留的 contents 提示，慢速列檔仍由原獨立可取消 child 擁有。使用者取消列檔不會被此次修正重新啟動。
- pending 提示只活在原 event coroutine；停止輪詢、斷線與更換連線會取消並丟棄，不帶入新相機。

不新增相機 endpoint，不更動 Canon 已宣告的 GET `/ccapi/ver100/event/polling`／GET `/ccapi/ver110/event/polling`，也不把 Simulator GET `/ccapi/events` 當成 Canon 私有契約。

## 可重現的離線因果證據

`CameraEventStateRecoveryTest` 使用真 `CameraViewModel`、`CameraRepository`、HTTP client，連接獨立合成 peer；可控 Main 排程與實際 OkHttp 提供故障邊界。

1. 單一 recording／ISO event → status GET 503 → 之後只有空事件，仍恢復新的 recording／ISO 與 capability。
2. 改為 capabilities GET 503；不得發布只有 status 成功的半份 snapshot，後續仍完整恢復。
3. 同時帶 contents；先發布控制狀態，列檔 peer 永不回應時，使用者 Cancel 必須終止實際 HTTP，後續空事件不得重列。
4. 連續四次讀取失敗；可控排程驗證 1／2／5／5 秒精確退避，期限前沒有讀取，且不消耗下一個 event。
5. 恢復期間斷線再連 B；B 連續至少三次空 poll，仍沒有舊 status／contents 工作或任何新相機寫入。

最初 fixture 漏掉既有 Simulator status 的必填 battery／media 欄位，造成 setup 失敗；該次不算 bug 的紅測證據。只補齊 peer 合約後，在未改產品碼的基準上得到 **4 失敗／1 通過**；只改 event loop 後，同樣 **5／5 通過**。每例明確要求沒有 mutating HTTP，不靠重播命令或延長原 timeout 過測。

另在既有 `CameraEventRefreshSessionTest` 加入 2 個真 Compose／ViewModel／HTTP cases，分別注入單次 status 與 capability 503。它們保留原 instrumented deadline，與既有 late-read／operation revision／media cancellation cases 一同驗證。

## 驗證狀態

- focused JVM：5／5 通過，0 failure／error／skip。
- 最終整合 JVM：570／570 通過，47 個 JUnit XML，0 failure／error／skip。
- Lint：0 errors／54 warnings，其中 4 個 ObsoleteLintCustomCheck 表示部分 Compose／Lifecycle registry 未執行；沒有 suppression，不能宣稱完整 custom lint 覆蓋。
- App／instrumentation APK：同次整合建置成功（6 分 17 秒）。158 個 Android source/config 檔案前後 fingerprint 一致：`0ae22b28986d5daa9b1d4f24631cc00cde1391ae654db47625e255d8fdc7ecd4`。
- 新增 2 個 instrumented cases 已編譯；執行及雙 API 精確 head CI 結果另記於 PR，提交本文件時尚未執行，不算通過。
- 沒有實體相機／手機驗證，也沒有宣稱修復過往亂對焦案例或 CPU-only 重連卡頓根因。

## 非目標與下一個缺口

本次只修「已消耗事件後的權威讀取恢復」。錄影開始／停止的模糊寫入結果、Live View 能力撤回後的停止責任、其他平台事件迴圈與系統選檔生命週期，各自需要獨立修正與驗收，不由這 5 個案例代言。

Release Assessment：以 v0.10.0 Development Preview 為基準，影響屬 patch；沒有改版號、合併或發布。PR ready 仍要求此分支精確 head 的 ci-complete 成功。
