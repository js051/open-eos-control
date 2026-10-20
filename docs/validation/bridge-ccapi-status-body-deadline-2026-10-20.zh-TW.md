# Bridge：指定 CCAPI status response-body deadline

前半保留原八路徑批次的來源、當時工具狀態與驗證結果；目前四個 optional GET 的後續範圍另列於文末，不覆寫歷史證據。

## 範圍與來源

- 基準為 dev `59f596953c9b58ca46863969477ee92637d54db0`。這是既有 CCAPI 讀取的有界修正，不新增相機命令、能力、API 權限或平台。
- 本批只改 `bridge/open_eos_bridge/ccapi.py`、`bridge/tests/test_ccapi_control_body_budget.py`、新增 `bridge/tests/test_ccapi_status_body_budget.py` 與本紀錄。Apple、Android、KVM、workflow、必要檢查與發布設定均不變。
- 產品目標延續[安全停止與既有 Stop 責任](../product-workflow-acceptance.zh-TW.md)：慢速狀態讀取不應持續占住同一 provider lock。本批名稱是「指定 status response-body deadline」，**不是「Stop 總延遲已修好」**。

## 已確認的來源路徑

`GET /session/{id}/status` 直接呼叫註冊中的 `CcapiSession.status()`。該方法的 `RLock` 與 held AF Stop、held AF 自動停止 timer、Bulb saved release、`close()` 共用。owned Bulb Stop 經過 `SessionManager` → `BulbRequests.stop()` → 原 session 的 `stop_bulb_exposure()`。Bulb admission 的 15 秒等待限制不會限制之後取得 provider lock 的等待。

原 `UrllibCcapiTransport.request()` 只有 configuration/control routes 使用既有 `_read_ccapi_body_with_deadline()`；其餘成功 body 使用 `read(max_bytes + 1)`。持續有 bytes 到達的 slow-drip 可以反覆滿足 socket idle timeout，卻超過請求的 body 時間預算。基準 `test_unrelated_get_retains_legacy_body_read` 明確把 battery GET 與 Live View GET 一起列為 legacy behavior：0.1 秒 timeout 下，約 0.6 秒的 body 仍成功。這是既有來源與測試所顯示的缺口，不是真相機事故或其他 CI 失敗的推定原因。

## 精確變更

僅 `GET` 且 URL path 以以下完整 suffix 結尾時，加入既有 monotonic deadline：

1. `/devicestatus/batterylist`
2. `/devicestatus/battery`
3. `/devicestatus/storage`
4. `/devicestatus/currentstorage`
5. `/contents`
6. `/shooting/information/recordable`
7. `/devicestatus/lens`
8. `/devicestatus/temperature`

query 不參與 suffix 判斷。預設 timeout 仍是 15 秒，byte ceiling、原 socket timeout、strict framing、redirect selection、HTTP status 與 Stop ownership 規則均不變。這也作用於其他既有呼叫者讀取同一 temperature/root-contents route，但不延伸至媒體檔案子路徑。

- 成功 body 在 deadline 邊界或之後抵達的 bytes 不得發表為成功。
- 已收到非 2xx 的 rejection，診斷 body 逾時或不可讀時仍保留原 HTTP status，不把明確 rejection 變成未知命令結果。
- body 成功、逾時、過大、截斷及錯誤均經原 finally 關閉。
- Stop 等待原 producer 結束後才執行既有 saved release，不 detach 原請求、不交給新 owner、不重新 Start。
- Stop ACK 後的 status tail 若失敗，provider 的明確 release ACK 不被抹掉；owned admission 原本保留的待確認結果仍由同 owner 的 Stop 恢復，且不重送已確認的 release。

這是刻意將上述 GET 的 body policy 從 idle-only 強化為 cooperative absolute deadline。舊 battery legacy case 改由新正向 deadline case 覆蓋；無關的 Live View GET legacy case、focus/REC write policy 與原控制 assertions 保留。

## 驗證設計與狀態

2026-10-20 UTC 的凍結候選離線 gate 已完成：

- 原基準 battery 與 Live View legacy policy：2 passed。
- 同一新測試對凍結基準：27 個預期 assertion failures，0 errors/skips；其中 24 個是指定八路徑 slow-drip／deadline 邊界，3 個是原 status producer 的 body-budget 與 held-AF／owned-Bulb／provider-close 路徑。只接受指定 assertion 類型，不以任意 nonzero 或 setup failure 當 RED。
- 候選 focused suite：627 passed；另原 strict-status／temperature contracts：8 passed，合計 635，0 failures/errors/skips。其中新 status-body tests 為 55 個，沒有放寬原語意 assertions。
- 三個程式／測試檔 hash 在執行前與凍結 manifest 完全一致，Python syntax 與 diff check 通過；本節只是 gate 後的文件補記。
- 第一個 wrapper 因既有環境沒有 `/usr/bin/time` 立即 exit 127，當時未執行 pytest。保留該 setup failure，僅換成 Python 標準庫 `time/resource` 計量，未修改 source/tests、未安裝工具。
- 修正後四段 child command 合計約 74.01 秒；最大單一已終止 child peak RSS 145,696 KiB（約 142.3 MiB），這不是 process-tree 同時總 RSS。外層 timeout、2 CPU affinity 與繼承的每 process 768 MiB address-space limit 保留，整個 gate 在原 5 分鐘時段內結束。
- Ruff 不在既有可用工具路徑，本批沒有下載／安裝；原 CI lint、完整 browser／Python／package gates 仍待原 workflow，不以 syntax check 冒充 lint。

1. 凍結基準 bridge source，將同一份新 tests 放入隔離的基準副本。先驗證舊 battery legacy case 原本成立，再由指定的新 deadline cases 取得預期 RED。將「刻意改變 battery body policy」與不應改變的回歸分開記錄。
2. 候選跑完全相同的新 tests，再跑原 control body/partial ACK、focus ACK、legacy REC ACK、Bulb/held AF ownership、release recovery、recording readback 與 CCAPI status regressions。
3. real urllib 只連合成 `127.0.0.1` HTTP peer。body 是有限的有效 JSON，約 0.6 秒、每 0.015 秒一 byte；fixture request budget 為 0.1 秒，不改 production defaults。
4. concurrency cases 走真正 `CcapiSession.status()`、`SessionManager` 與 `BulbRequests`。test-only transport observer 保留真 I/O，在原 request finally 即將離開前設 barrier。即使 body 已關閉，saved Stop 仍必須等待此 producer；放行後依序觀察 body close → request exit → saved Stop enter。不能只靠 elapsed threshold 判斷 ownership。
5. fixture 未 advertise optional card-selection/beep/display-off operations；普通 settings JSON 與非 slow-drip reads 立即回應，並斷言實際 GET 集合只包含 batterylist、storage、shooting/settings。這是測試前提，不是那些額外讀取已被本批限制的證明。
6. deterministic clock cases 驗證八個 suffix 的 exact deadline 與 late-byte rejection；另驗 unrelated method/path、redirect、原 HTTP rejection、size/truncation、失敗 Stop 原 owner 保留，以及 Stop ACK 後 status timeout 的恢復。

離線上限：serial pytest、最多 2 CPU、768 MiB、5 分鐘；使用既有隔離 pytest 8.4.2 與工具，不下載或安裝。必要的 desktop/browser/Windows package 驗證仍交原 workflow，不以 focused gate 取代。

## 保留界線與後續依賴

- cooperative deadline 在 buffered reads 前後檢查；不宣稱強制取消 DNS、connect、headers 或某一次正在 blocking 的 read。
- 每個 response 有獨立預算。多個版本 fallback、status 中多個依序讀取、lock admission 與 Stop 後 status tail 不合併成一個總時間保證。
- `status()` 的 `_load_settings(force=True)` 仍可能讀取未在本批列入的 optional `/functions/cardselection/stillimage`、`/functions/cardselection/movie`、`/functions/beep`、`/functions/displayoff`。這些是精確的後續來源範圍，不因本批 GREEN 宣稱所有 status lock 路徑都有 absolute deadline。
- discovery、event polling、Live View GET、媒體串流及未列出的寫入政策不變。不新增 retry，也不變更收到 rejection 後原本的 endpoint fallback。
- loopback 不證明物理快門停止、光學 AF、真相機網路可靠性或跨機型相容性。Apple picker 舊 crash 的非重現、Android native color 尚待 device evidence，與本批獨立。

## 交付與 Release Assessment

候選已實作並通過上述離線 gate；本紀錄凍結時尚未推送或取得本批原生 CI 結果。正常 dev 交付前必須重查 exact remote head、無 open head:dev PR、四檔 hash/allowlist，保留 pre-commit/pre-push secret scan 與非 force transport。

按現有 path filters，這四檔只觸發 `Desktop dev validation` 與 `Windows dev validation`，不觸發 Apple、Android host/device 或 KVM。歷史 Desktop run `39342666839` 的 desktop job 為 11 分 50 秒，Windows run `39342666985` 的 bundle job 為 1 分 38 秒；本次規劃約 12–20 分鐘另加 queue，不保證排程時長。這兩項是 supplemental dev validation，**不等於 PR `ci-complete`、main accepted 或 release**。

Release impact 建議為 `patch`：修正既有讀取的資源/時間界線，未新增 wire capability；本批不變更版本、不合併 main、不建立 tag 或 prerelease。正式後續整合仍需對完整 release delta 重新做 Release Assessment。


## 後續：四個已廣告 optional status GET

基準：`134fd67b8b99bf0d4c3daad482f6fb8c0af8961f`。既有 `status()` 持有 provider `RLock`，並呼叫 `_load_settings(force=True)`；camera discovery 若提供同版本的 GET/PUT 配對，便會讀取：

- `/functions/cardselection/stillimage`
- `/functions/cardselection/movie`
- `/functions/beep`
- `/functions/displayoff`

這些真實 status 路徑與 held AF Stop、Bulb saved release、`close()` 共用同一把鎖，但原先未命中 deadline selector，仍以 `read(max_bytes + 1)` 收取成功 body。有限 slow-drip 可持續占住原 producer，讓已要求停止的 caller 繼續等鎖。原八路徑 fixture 刻意未 advertise 這四組操作，因此它的 GREEN 不涵蓋本次缺口。

產品僅向 `_STATUS_BODY_PATHS` 加入四個完整 suffix，沿用原 GET-only 選擇式及 `_read_ccapi_body_with_deadline()`。沒有改相機廣告／版本配對、`_first_json` 的 HTTP rejection fallback、PUT、strict control、redirect、stream 或 production 15 秒預設。既有 `BridgeError` transport timeout 仍往上傳遞；optional 不代表吞掉讀取失敗，也不代表可以猜寫入能力。

### 可反駁的範圍

- 四個 route 各自驗 finite slow-drip、exact deadline、open 已消耗預算與原 HTTP status/redirect；其他 method（尤其四個 PUT）不加入本次政策。
- 真正 status caller 先完成快速 battery/storage/aggregate-settings，再讀已廣告的 optional endpoint。真正 saved Stop caller 必須等原 body 關閉及原 producer barrier 放行後才進入，不可只看 elapsed time 或改由新的 producer 接手。
- 保留原 saved operation／owner、held AF／owned Bulb／close、Stop rejection 後恢復、已確認 release ACK、原 endpoint fallback 與不重送 Start。未廣告或版本不配對時不查 optional route；正常 ability/readback 仍由既有契約驗證。
- 每個 body 是獨立 cooperative 預算。DNS/connect/headers、單次 blocking read、逐 request 累積與 lock admission 沒有變成 hard deadline，也不宣稱 Stop 總延遲固定。

### 後續驗證狀態

2026-10-20 的凍結 baseline/candidate gate 已完成：

- 同一組 167 個 status-body cases 對 `134fd67` 原 production 取得 **40 個預先指定 assertion RED、127 PASS**，沒有 error／skip。原 55 個 node IDs 全部保留且在 baseline 仍通過。
- 40 個 RED 為四條 route 的 finite slow success（4）、exact／late deadline（8）、open 已用完預算（4）、slow HTTP rejection diagnostic（4）、三種 producer／saved-Stop 次序（12）、拒絕 Stop 的恢復前置條件（4）及 release ACK 後 status tail（4）。先封存精確 IDs／failure markers，再執行 baseline，不以任意 nonzero 代替反例。
- 候選 **739 個 focused regression PASS**，包含全 167 個 status-body cases；另 **21 個既有 status／card-selection／device-function 契約 PASS**。四個 optional route 的正常能力、同版本配對、readback／寫入前刷新與 malformed／unsupported fallback 均保留。
- 原三個 ownership tests 抽入共用 helper，原 owner／receipt／join／recovery／cleanup assertions 維持 AST 相同；只有 fixture-domain assertion 加入明確提供的 optional suffixes，原 wrapper 仍使用空集合預設，另強化 saved-operation method/path identity。
- Production byte diff 僅四個 suffix 與一行說明；移除該插入段後與凍結 production byte-for-byte 相同。原 real-picker UI SHA-256 仍為 `05a60bbd2bfc3064451fa17f094a2435d72b541cf10a41989740b8609487af37`。`git diff --check` 與原完整 `ruff check --no-cache . ../scripts/validation` 通過。
- 第一個 collection setup 因 repo `addopts=-q` 加上 collector `-q` 只輸出總數，尚未執行 baseline 即停止。保留 exit 1 receipt，僅以 `-o addopts=` 修正 collection verbosity，未改 source、tests、assertions 或 failure whitelist，也沒有重設原 295 秒 deadline；這不是產品 RED。
- 成功 invocation 為 93.94 秒，含上述 setup 的 cumulative wall time 為 134.45 秒。兩 CPU affinity；峰值 sampled process-tree RSS 為 159,788 KiB（逐 process 加總，shared pages 可能重複計入），低於 768 MiB；結束 free space 為 2,322,509,824 bytes，保留 2 GiB floor。

本次五檔還包含 README 的目前範圍說明，以及 [iOS RTP 到期計數紀錄](ios-rtp-audio-expiry-accounting-2026-10-20.zh-TW.md) 的已取得 native receipt 補記；後者不改 iOS source／tests，也不獨立觸發 Apple。本次 serial offline gate 維持最多 5 分鐘、2 CPU、768 MiB sampled process-tree RSS 與 2 GiB free-space floor，僅使用既有 pytest/Ruff，沒有安裝工具或開相機。

本地驗證已通過；遠端 Desktop／Windows 結果仍待本次發布後取得，不能由本地結果先宣稱 CI GREEN。發布前須重查 dev HEAD、無 open head:dev PR、精確五檔 hash 與正常 hooks，再走非 force dev push。依目前路徑規則只觸發既有 Desktop／Windows，約 12–20 分鐘另加 queue；沒有 workflow、Apple、Android、KVM、main／release 變更。這仍是 supplemental dev validation，不等於完整共同 HEAD 的 PR／main／release 或實體相機驗收。
