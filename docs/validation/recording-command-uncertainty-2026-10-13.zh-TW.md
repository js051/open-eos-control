# REC 命令結果未知：保留明確 Stop 責任

## 已重現的原 P1 缺口

PC 原 PR237 的 `df8080a3d59e7a969939496b425cadfcdfe372ad` tree，在真 production handler 的受控模型中，兩種已生效 Start／遺失回應（相機 hop 與 Bridge hop）都產生 `[start, start]`，而下一次明確操作應為 `[start, stop]`。2026-10-13 07:40:21 UTC 兩例均 red；模型的獨立 peer 狀態先變 true，App 只拿到錯誤與旧 ACK cache false。此為 handler 反例，不把它稱為 browser、真 HTTP 或真相機證據。

原本已驗證的 `RECORDING_STATUS_READBACK_FAILED` 只表示命令已收到 ACK、後續讀狀態失敗；不能用該分類描述本輪未知結果，更不能拿先前 false cache 解除可能仍在錄影的責任。

## 最小公開契約

- Bridge 的 REC mutation 呼叫若收到 `CCAPI_UNREACHABLE` 或 `CCAPI_RESPONSE_TOO_LARGE`，以新增 `RECORDING_COMMAND_UNCONFIRMED`／HTTP502／VIDEO_RECORDING／ccapi 回覆。這表示可能生效，不是假造 ACK。能力／溫度等前置檢查仍在此捕捉範圍外；明確相機 HTTP 拒絕保留 `CCAPI_HTTP_ERROR`。
- 不更動原 recording boolean 的 ACK cache 語意，不加端點、背景命令、自動重播或自動 Stop。
- PC 以原 session object 保留 `acknowledged:false` 的 REC recovery。Bridge hop 網路失敗、502 回覆缺失／損壞 error envelope、沒有明確 HTTP 狀態的失敗回應保守保留未知；收到完整 envelope 時只接受新的精確四欄位契約，原始 CCAPI_UNREACHABLE／CCAPI_RESPONSE_TOO_LARGE 可能来自零 REC 的 preflight，不能在 PC 自行升級為已嘗試 REC；明確 HTTP401／403／503 拒絕不被改稱 ACK 或未知命令。
- 未知結果時只允許明確 Stop，保留原媒體 Stop baseline。即使 Refresh 回 false／true，也不能僅因 cache 清除未知責任；收到本次明確 Stop 的成功回應後才循原流程解除或轉成已 ACK、狀態未明的恢復。
- 舊 session 的晚回覆不能改新 session。退休連線只保留上一台相機仍需檢查的警告，不將其 Stop 發到新相機，也不自動停止。既有持續控制警告文字明確包括錄影。

## 受控驗證

- 獨立來源審查找出 preflight 過度分類與 Bridge 502 body 遺失低估責任。加入真 production `api` 邊界控制後，修正前 99 例中 9 例失敗；精確 tuple／502 envelope 修正及補入缺失 envelope 控制後，109/109 通過。此為受控 fetch／production handler，不是 browser 網路證據。原始兩例 red 使用較粗略的相機傳輸錯誤模型；最終正例已換成新 Bridge 精確契約，另明確驗舊 raw error 不自行變成 UNKNOWN。

- 最初兩例 red 已保存；相同 production-handler 斷言在修正後 green。
- 完整 capture/review handler 群目前 109/109 pass，包含明確拒絕、舊 cache、Stop 拒絕、原 baseline、退休 owner／同 ID 新連線與不發自動命令。
- 完整既有27個 Node modules 通過；最後補入的 retirement／late-result 控制再由上述109例執行。正式交付前仍跑最終整批。
- 真 urllib／FastAPI ASGI 與獨立 TCP peer 的42個 REC/Bulb測試通過。Peer 明確先變更自己的 recording 再丟回應；舊 false cache 不等於 peer false，只有明確 Stop 令 peer 停止。最初新 fixture 對既有503錯誤碼猜成 CCAPI_REQUEST_FAILED，實際為 CCAPI_HTTP_ERROR；已按實際契約修正，保留該失敗紀錄。
- Bridge 審查補充：已收到非 2xx status 時，最多讀取 2,000 bytes 診斷內容，body 損壞不抹掉已知拒絕。403／503 × Start／Stop × 三種 body fault 的12個控制先全部 red，再全部 green；六個損壞成功回應仍為 UNKNOWN，溫度 preflight 失敗則證明一個 GET／零 REC。Ruff 本地未安裝，未執行，交由原必要 CI。
- 最終27個 Node modules 重跑通過。另嘗試廣泛 unittest discovery，終態 exit1／99個測試項目、13個 import errors（現環境缺 pytest／av）；不列完整 backend pass，不新增安裝或跳過原 CI。
- 新 browser 英／繁中四個 lost-hop 分支已加入原完整旅程：camera hop 在 peer 生效後斷 socket；Bridge hop 在後端成功回覆後以 browser route 丟棄該回應。各自檢查獨立 peer true、cache差異、零重播、明確Stop及最後peer false。尚未執行此新版 browser CI，不以 Node／backend 結果代替。

這些證據不保證物理相機的錄影狀態、跨相機韌體行為或外部檔案 provider。三端來源與 exact-head 結果分開記錄，不將相同字串或無檔案衝突當作 combined runtime 通過。

## 最後的 envelope 相容性核對

公開 `ErrorDetail` 的必要欄位是 code 與 message，正常 Bridge handler 兩者皆有。code-only、錯誤型別或空白 message 的 502 不能當作完整拒絕證據。PC 與 Android 對齊 iOS 的完整-envelope 檢查；PC 三個新增不完整 message 控制先 red，修正後全部109個 handler 控制通過。完整既知拒絕與 near-match tuple 控制加入合法 message，仍必須維持原分類，沒有以缺欄位 fixture 偷換測試目的。

`a7f6eed55f3a4ebb964543a0bd673e0193c376cf`／CI `38368772954` 已於08:24:23～26 UTC通過真 browser 的英／繁中 camera-hop／Bridge-hop 四個遺失回應旅程，desktop backend、打包與Windows gate也已成功；該版本尚未包含上述最後message檢查，其成功不能替代下一版 exact-head 驗證。原已知失敗與本地缺依賴紀錄保留。

## 完整 REC 分類表收斂

前述 message 檢查版 `8e87e6cca99b1cde6d19cb78e7cb3d94c753f56b`／CI `38370509364` 的全部適用 gate 與 ci-complete 通過；四條真 browser lost-hop 旅程於08:39:43～45 UTC通過。該結果不涵蓋下列後續分類修正。為避免逐條小改反覆CI，本輪一次檢查跨端 status／error envelope／成功本文／取消／退休路徑，收齊以下公開契約。

| 邊界 | 結果 |
|---|---|
| 401／403／503，包括 ACK-like code | 已知拒絕，不建立新 ACK 或 UNKNOWN |
| 完整502 envelope＋精確 ACK code | 已接受，後續狀態讀取未確認 |
| 完整502 envelope＋精確 UNKNOWN 四欄位 | UNKNOWN，只保留明確 Stop |
| 完整502已知拒絕／preflight error | 一般錯誤，保留原本已有的責任 |
| 502 body遺失／JSON或必要欄位不完整 | UNKNOWN，不猜已拒絕 |
| 完整Bridge 2xx物件，recording為Boolean | ACK與回傳狀態 |
| 完整Bridge 2xx物件，recording缺失／null | ACK但狀態待確認 |
| Bridge 2xx非物件或recording非Boolean且非null | UNKNOWN，保留原snapshot |
| 本次REC之前取消／退休 | 不發新REC、不把舊Stop轉移到新連線 |
| 本次REC已送且結果仍不明 | 同owner保留UNKNOWN；退休只留上一台相機警告 |

PC的ACK錯誤分支同樣綁定實際REC owner；Bulb stop不能因相似code取得錄影責任。code／feature／engine精確比對，不裁掉前後空白來擴大接受。status Refresh遇非Boolean recording不得解除已有ACK或UNKNOWN恢復。有效null／缺欄位保持原相容行為，不以嚴格化刪除該契約。

受控production handler/API新增三組反例分別實跑6個非502 ACK誤分類、12個成功本文shape問題、1個非REC owner問題，修正前皆失敗。修正後連同兩方向shape、padded tuple、nullable正向與status-refresh負例共160項通過。這仍不是物理相機或手機 runtime 證據；iOS／Android各自的實際編譯與測試結果必須由各自新head取得。
