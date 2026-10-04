# Android Bridge 快門停止恢復

## 使用者流程與失敗恢復

此批接續 PR #199 的 additive Bridge 協定，基底為同一修正分支；不改正常 AF 選擇或背景曝光政策。原 client 忽略釋放警告且無 stop-only 恢復，與 server 已具備的停止責任不對等。

- 開始 Bulb 前保存原 Bridge session 的釋放責任。開始回應遺失／格式錯誤後，不重送 start；明確顯示停止未確認。
- 暫停預覽，阻止拍照、曝光設定、AF、錄影、Live View start 等衝突寫入；保留同 session 的 Stop、read 與 teardown。
- Stop 不依賴最新 mode 或 Bulb capability；已確認 active 切到 Manual 或能力消失時仍可停止。
- 失敗可再次手動 Stop。只有本次 Stop 的回覆，或緊接其後同 session、no-cache 的 GET status，同時具有 literal false 的兩個證明欄位才解鎖。
- 只看到一般 status idle、缺欄位、null 或字串 false，不當作未知曝光已停止。已確認 legacy start/stop 的正常流程仍相容；legacy ambiguous 狀態要求檢查機身並更新 Bridge。
- 成功恢復不閃拍攝成功，也不把未 ACK 的 start 記為完整 Bulb 證據。release 確認後的狀態刷新錯誤另行顯示，不恢復已清掉的風險。
- 斷線清理等待已擁有的操作，保留上一連線警告。新相機不能收到上一連線的 stop；晚回應與舊錯誤不得更動新的 owner。

## Production 契約

新增 BridgeShutterReleaseSession，將 mutex、revision、confirmed start、unconfirmed release 綁定 session 物件，而非只比可能重用的字串 ID。一般狀態只能提升風險；同次 Stop 的明確新證據才可清除。

POST /v1/session/{id}/bulb/start、POST /v1/session/{id}/bulb/stop、GET /v1/session/{id}/status 與 DELETE /v1/session/{id} 保持既有路徑。相機 mutation HTTP 使用 one-shot body、停用自動連線重試及 redirect；read-only GET 保留既有恢復。不新增 endpoint、權限、持續存取或依賴。

ViewModel 在 connect／手動刷新／事件刷新／操作回覆採納停止警告。後續 capabilities 失敗不能吞掉已收到的風險，也不能拆掉唯一仍能 Stop 的連線。UI 支援英文／繁中警告與模式無關的停止入口。

## 因果與回歸證據

原碼獨立 HTTP peer 的首批 8 例中 7 例失敗，既有 legacy happy path 通過。peer 不匯入 Bridge server 實作，刻意在收到 mutation 後丟失回應、回 408/503/redirect、提供缺失或錯誤型別欄位，並控制舊回覆的次序。

最終新增 19 個 JVM production-client cases：模糊開始、反覆停止、fresh readback、legacy、status-only 警告、capability 失敗、取消後清理、session ID 重用、晚錯誤、read-only retry 與 mutation exact-once。與既有相關契約合跑 63／63。

新增 6 個 Compose App → ViewModel → Repository → Bridge HTTP cases，包含未確認 start 不重播、停止失敗不恢復預覽、320dp／2x 字體恢復入口、mode／capability 變動、A→B 隔離與正常 active Stop。這些是 Android framework／synthetic HTTP 證據，不是實體相機。

## 驗證快照與邊界

原基底 3d37e83 的完整本機 aggregate：App JVM 584／584、Camera Import contract 14／14；0 failure／error／skip。Lint 0 errors／54 warnings，含 4 個 custom lint registry 版本不合，未完整執行；沒有 suppression。App 與 AndroidTest APK 建置成功。六個 instrumentation cases 當時只編譯，未在本機執行。

後續以 fast-forward 納入 PR #199 的 multipart cleanup 修復，12 個 adapter 改動檔 hash 均保留。共同基底 91abc93 的完整 aggregate：App 585／585、contract 14／14、Lint 0 errors／54 warnings（4 registry 限制不變）、兩個 APK 通過；161 個 Android source/config 前後 hash 一致。這與原 584 例是不同快照，不相加。精確 head instrumentation／ci-complete 尚待執行。

沒有實體 EOS／Android 手機、跨機型或光學對焦新證據。Bridge 的非 CCAPI engine 不因此取得同等相機端停止保證；process death／永久斷網仍需使用者檢查機身。這不是全產品所有網路讀取／逾時的完整稽核。

Release Assessment：v0.10.0 Development Preview 之上的 patch；不改版本、不合併、不發布。PR ready 需精確 head 的 ci-complete 與新 instrumentation 執行成功。

## 審查後補上的取消與恢復補讀期限

獨立故障 peer 持續少量傳送 response body，因此不觸發 idle read timeout。真 production client 的 Stop 先收到完整 502，接著新增的 fresh status 補讀一直持有 NonCancellable 與 session mutex，阻擋 retry／DELETE；一般 status 的 coroutine cancel 也未取消實際 HTTP body read。

調整後的故障回歸 4 例中 2 紅：5 秒＋1.5 秒排程餘裕後 Stop 仍未結束、DELETE 尚未到；ordinary cancel 在 1.5 秒內未完成。frozen baseline transport control 約 0.25 秒結束，這只是原失敗傳輸路徑的對照，不冒稱整個舊 checkout 已重跑。finally 保存並取消實際 Call，沒有靠 peer 自然讀完才使測試退出。

最小修正：requestJson／requestOk 的 cancellation watcher 涵蓋 Call 與 body；正常完成先解除 watcher，避免把成功誤標取消。只有 Stop 失敗後的額外 GET /v1/session/{id}/status 有 5 秒 whole-call budget，且保留既有更短設定。這是 App 願意等待額外證明的政策，不是 Canon 時序保證。

到期仍為 release unconfirmed，釋放 mutex 後可再明確 Stop 或 DELETE。POST /v1/session/{id}/bulb/stop 仍受 NonCancellable 保護、原預算不變；普通 GET、媒體及其他命令未被改成 5 秒。沒有自動重播 start，也沒有將 timeout 判成停止成功。

focused 42／42 通過：新增 deadline 9、既有 recovery 19、client 14。涵蓋 expiry → 維持風險並阻擋新寫入 → 再次明確 Stop 確認 → 解鎖、fresh 成功、原 300 ms 預算、兩類 body cancel、protected Stop 不被呼叫端取消。最終完整 aggregate 與精確 CI 另記，不以 focused 通過取代。

## 最終本機 aggregate

- App JVM 594／594（49 suites），contract 14／14（1 suite），0 failure／error／skip；4 分 45 秒。
- Lint 0 errors／54 warnings，4 個 custom registry 執行限制仍存在；App 與 AndroidTest APK 均成功。
- 162 個 Android source/config 前後一致，manifest SHA256：2a647230622e3ada81b3e2226a8f3f2ce6d61cfcaaaac8634383d5bf9bf4fb70。再 fast-forward 納入 PR #199 的 test-only 尾端至 180dbfa，162 個 hash 全部相同，沒有以較舊 Android source 充當最終驗證。
- App APK SHA256：5eb743fdbf9440c98fcf8d2524692d6fabc30fea7f29988d1e9ead4276b5a720。
- AndroidTest APK SHA256：f88172a2dfd74e1659b8b34f7bdf0665351b393beb2d7847b95391b41aa81505。
- 本機沒有執行 instrumentation。六個新增 runtime cases 與精確 remote head ci-complete 接續由正常 CI 驗證，尚不能算 PR ready。
