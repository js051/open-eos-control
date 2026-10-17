# Android CCAPI Live View 控制傳輸有界化

## 已核實問題與最小修正

基線 `4163aad87ca88a3c3bc78ffd486d1d550b0f8838` 的一般 JPEG／multipart／RTP Start、Stop 經 `postOk/deleteOk → requestOk → mutationHttpClient`，不會進入維護、錄影、設定或單張拍攝的有界路徑。一般 mutation client 只關閉連線重試；預設 factory 沒有 whole-call deadline。`requestOk` 也沒有這些命令的 cancellation watcher，且以 `body.string()` 無界讀取。

`stopLiveView` 原本以 NonCancellable 等待原始 Stop closure，`CameraRepository.disconnectLocked` 又直接等待 Stop。若相機持續慢速回應，取消 Start 或 Disconnect 可能一直等同步 producer。本次不解除這個 join，也不新增 detached callback。

本批只在下列既有控制呼叫設定 `liveViewControl = true`：

- JPEG／multipart 的一般 Start，以及 HTTP 400 的 display-only／尺寸降級 Start。
- 一般 DELETE Stop 與 POST-off Stop。
- multipart closeStream DELETE，包含原有 maintenance／一般兩個分支。
- RTP 的 Start 與原始 Stop closure。

這些呼叫選用既有 single-attempt client，總 call timeout 最長 15 秒，保留呼叫端更短期限，禁止自動重試、重新導向、authentication replay。每個回應最多讀取 65,536 bytes，檢查已宣告長度是否完整。Live View 仍接受原有純文字 HTTP 錯誤，不把它改為嚴格 JSON 收據，避免破壞 HTTP 400 尺寸降級語義。

取消 watcher 取消原 Call，但呼叫仍同步等待 execute/body/use 結束。NonCancellable Stop 仍靠 producer 的 whole-call deadline 結束。未知 Stop 保留 `pendingLiveViewStop`，拒絕 AUTO fallback 與新 Start；明確 Stop 重試才可清除。RTP 的原 finally session.close 與 multipart 原本的本地 close、closeStream 後一般 Stop 順序不改。

## 已執行的證據

2026-10-17 UTC，以既有且 SHA-256 校驗的 Kotlin/JUnit 工具離線執行；每個 JVM 限 256 MB／1 CPU。

1. `run-android-liveview-transport-host.py` 對同一套 fixture 的基線：compile exit 0、JUnit exit 1，12 案均於指定 assertion 失敗。修正後：compile exit 0、JUnit exit 0，12/12 PASS。
2. 逐字萃取 production `startLiveView`、JPEG／RTP entry、Start cleanup、Stop／owned Stop、`postOk/deleteOk/requestOk`，編譯完整 bounded readers。沒有用 fixture 直接傳入新的 transport flag。
3. 12 案涵蓋取消 Start 進原 Stop、Stop 仍需 join、已知／未知長度超量、宣告長度截短、RTP Stop 後才 close、Stop 失敗保留 owner、取消 Stop 不分離 producer、AUTO 未知 Stop 禁止 fallback／新 Start、POST-off 與純文字 HTTP 400 尺寸降級。
4. 共用 `requestOk` 的既有 still-transport 回歸 3/3 PASS；包含原 manual release 的 producer 順序、join 與 body 上限。
5. 分開的 source guards：既有 strict client 15 秒設定、production `newCameraCall` 的 strict-client／one-shot routing，以及 multipart 的兩個 closeStream DELETE opt-in 與 Start／Stop 共用 wiring。
6. Kotlin PSI 僅語法檢查 production client 與新增原生測試通過；不等於完整 Android type-check 或原生測試執行。`git diff --check` 通過。

[輕量收據、原始 JUnit 輸出與 manifest](evidence/android-liveview-transport-2026-10-17) 保存 production／method／fixture／工具 SHA-256、退出碼與測試邊界。交付收據將 filename 與 SHA 分列，保留所有值；manifest 同時保存原始收據 hash。JUnit 輸出只正規化結尾空白行，manifest 另保存未正規化輸出的 hash。完整本地 evidence 保留，未提交可重建的 JVM binary。

可重現 runner：指定既有 installation receipt 所在 `--jars`，以 `--phase baseline --ref 4163aad8` 及 `--phase current` 執行至不同 `--out` 目錄。baseline 模式同時核對 12 個指定失敗訊息，不能把缺依賴或編譯錯誤算成 red。

## 原生測試與未驗證邊界

`CcapiLiveViewControlTransportTest` 已編寫 8 個 MockWebServer 案例，但本工作環境缺少 OkHttp／Okio／MockWebServer 依賴，依授權未下載、未執行：

- Headers 後取消 Start，需 join response 並完成原 Stop。
- 取消 Start 仍等待慢速 Stop body。
- 預設無 whole-call timeout 的 client，連續滴流 Stop body 應由 production 15 秒上限終止。
- 保留呼叫端 250 ms 期限與 Stop retry obligation。
- 未知長度超量 multipart Stop 阻擋 AUTO fallback。
- 實際 multipart closeStream／一般 Stop Call 的 15 秒 timeout wiring。
- 純文字 HTTP 400 保留尺寸降級。
- 控制命令不跟隨 redirect 重播 Start。

Host 使用 synthetic Call／Response／InputStream、frame readiness、RTP session 與前置查詢；它驗證 production 控制流程與 producer join，不能證明真實 OkHttp socket 15 秒期限、Android decoder 或實體相機關閉時序。multipart stream 本體沒有被 host 執行，其 wiring source guard 與未執行 native test 必須分列。

非目標：temperature／capability 前置 read、RTP SDP `getText`、其他命令、整條 Connect／Disconnect 的總期限、完整 Android／CI／實機驗收。不宣稱所有等待點已修復。

## Release assessment

影響為 patch：既有 Live View 控制在取消／關閉時的傳輸限制修正。本批沒有改版本、合併或發版；完整 Android、exact-SHA CI 與實機證據尚待驗證，不宣稱 PR ready、main accepted 或 preview released。
