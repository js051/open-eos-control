# iOS 多選素材中繼資料批次與讀回證據（2026-10-15）

## 狀態與範圍

本地實作位於凍結候選 `f1e6d938c8704ee2b5098981ea0bd17272285122` 之上。這份變更不覆寫該候選、不推送、不觸發 CI、不合併、不改版本、不發版，也未安裝新工具。

新增已載入素材的明確多選評分 0–5、保護／取消保護、絕對旋轉 0／90／180／270 度、封存／取消封存。沿用現有連線所宣告的能力，不增加傳輸方式或相機能力。刪除、整卡掃描、新 transport、真機操作、原檔下載／Files 匯出邏輯與自動 JPEG 驗收不屬於本批次變更。

## 使用者流程

- 素材畫面的原有多選區新增整理選單；英文及繁體中文均有完整動作、審閱、結果及取消文案。
- 開啟審閱時，凍結選取順序、byte-exact ID、當前已載入項目的中繼資料及目標值；以當前已載入副本取代 stale selection 副本。相同 ID 只保留第一次選取，Unicode 正規化相等但 bytes 不同的 ID 保持不同。
- 審閱顯示選取、篩選隱藏、未載入數量及每項名稱／確切 ID。之後的排序、篩選或選取變化不擴張或縮減已確認清單。
- 超過 1,000 個不同 ID 時，以單一拒絕摘要明列整批未開始及完整數量，不截斷成前 1,000 項，也不為被拒絕的清單累積結果列。未載入、未知封存狀態、未支援、無效或明確不可寫項目都有結果列，不會為了補資料另行查找或掃描相機。
- 每項最多一個寫入，最多一項 in-flight。直接 CCAPI 沿用最多三次讀回；Bridge 採現有 PUT 回傳的讀回證據。沒有任何自動 mutation retry。
- 第一個已送出但未確認的結果停止後續項目；已確認結果及尚未送出的列保留。
- 「取消剩餘變更」禁止後續派送。已派送項目仍可完成有界讀回；同 owner 的晚到確切確認可以保留。取消不代表回復已送出變更。
- 連線 owner 變更會原子禁止尚未派送的請求，取消舊 task，將舊 in-flight 保留為未確認，且拒絕舊回覆改寫新連線畫面。先前確認／失敗證據保留。
- 結果歷史跨連線合計最多 **20 批／2,000 列**。admission 在任何 append／派送前檢查；容量不足時只覆寫單一固定大小拒絕摘要，不能因重複拒絕而增加 ledger。原有確認、未送出、未確認列均不會自動淘汰。畫面結果使用 LazyVStack。
- 可逐批清除已結束記錄；任何批次仍在執行時，所有清除入口及 production 方法都拒絕清除，避免移動 active executor 的索引。清除必須再次確認明確警告：記錄無法復原、不是 undo、不撤銷相機命令、未確認變更可能已生效。取消清除保留完整記錄；明確清除只刪所選批次，保留其他批次。owner 已退休的未確認批次同樣需要明確確認，晚到回覆不能重建記錄。
- 清除後替換 metadata 專用 draft epoch，所有舊審閱（包含已消耗、未確認及尚未送出的審閱）均拒絕重播；不使用無界 consumed-ID Set，不改相機或其他 workflow generation。
- 單項整理的 sheet／preview-actions presentation 在建立時凍結 owner；所有保護、評分、旋轉、封存 Task／Picker callback 必須傳該 owner。setter 沒有取得 current owner 的預設值，在 begin、派送及回覆套用前檢查 owner；連線退休原子取消尚未派送的 single attempt。舊 callback 即使在新相機重用同一 ID 後才執行，也不會送出或改動新 UI。delete／download 入口未擴改。
- 再次明確確認屬於新的獨立操作；同一審閱確認不可重播。結果僅保留於本次 app 執行期間，不建立可自動恢復的 mutation worklist。

## 讀回與資格規則

1. 只有同一 byte-exact ID 加上要求欄位的精確目標值才能確認。
2. Bridge 必須提供原始確切 ID。Canon `kind=info` 未提供 ID 時綁定本次確切請求 endpoint；若明確提供 ID，則仍必須逐 byte 相同。
3. Bridge／simulator listing：評分接受 JSON integer 0–5 或精確 ASCII 字串；旋轉接受 integer 0／90／180／270 或相同字串；flags 僅 JSON Boolean。
4. Canon／simulator `kind=info`：僅接受既有字串 `off`／`1`…`5`、`0`／`90`／`180`／`270`、`enable`／`disable`。
5. 新的 scoped media decoder 在 Foundation 數字轉換前檢查原始 token，保留 `3.0`、`3e0`、精密小數、overflow、負值等未知證據，避免 NSNumber coercion 把錯誤資料變成成功。重複鍵拒絕；只接受 UTF-8 JSON。一般相機設定 JSON helpers 未改。
6. metadata info／mutation response 上限 1 MiB；listing decoder 保留既有快照上限。scoped decoder 上限 64 MiB 並保留既有 grammar／duplicate-key／512 層限制。這些是拒絕邊界，不代表已實跑最大資料量或 Darwin parser。
7. `writable=false` 否決所有操作；`ratingWritable`、`protectionWritable`、`rotationWritable`、`archiveWritable` 的明確 false 只否決對應操作。缺欄位不新增能力。封存另需明確已知 Boolean archived。
8. Bridge public client 仍回傳原始解析的欄位，保留既有 paired folder provenance／缺失欄位契約；App 的單項與批次在 exact-ID＋目標值確認後，只把 requested field 合併到當前 exact-ID loaded record。不相關 partial response 的省略、null 或矛盾值均不抹除既有 writable veto、folder 或其他 metadata；再次單項操作也檢查當前 veto。
9. 每次送出前重新檢查目前載入資格及現有 capability；取消／owner retirement 與派送 marker 使用同一原子鎖，marker 只代表「可能已送出」，絕非成功。

## 測試與證據界線

本地既有 Python 環境、CPU8 序列執行：

- 共用 fixture integrity、iOS 新 source guards、XcodeGen path guards，以及 JPEG／media type／capture review／日期 source regression：63 PASS。
- 全量 `scripts/ci/tests`、`scripts/validation/tests`、`scripts/contracts/tests`：143 PASS；另有 1 個既有 `test_tree` helper 回傳 dict 的 PytestReturnNotNoneWarning，未隱藏或改寫。
- Ruff：PASS。`git diff --check`：PASS。
- 初次 source token 比對失敗、兩個原有資源計數失敗及修正後結果均保留在外部證據目錄；沒有刪除或改寫舊失敗紀錄。
- 主 fixture：200 metadata、66 readback、10 batch、3 roster vectors，SHA-256 `2d30173f8abb0538f1aa320cfce24e2fb0e01f86ef6fc54db93a052ed2fdd401`。
- 資格 fixture：25 eligibility、2 stale-loaded-snapshot vectors，SHA-256 `c7c6e27e7455ff12989cea4fab48f97fd103344ff442987a37cc4a32e3f99a1d`。

已編寫 `MediaMutationContractTests.swift`（20 個 App 測試方法），直接呼叫 production Bridge／CCAPI parsers、CameraSession 與 CameraAppState executor，覆蓋上述 vectors、所有目標值、取消／owner loss、無重播、超限、stale selection、單項 writable veto、escaped／duplicate raw keys、深度與大小拒絕。P2 回歸另涵蓋跨 owner 的 20 批上限、2,000 列上限、連續 100 次拒絕不增 ledger、取消清除、in-flight 禁止清除、明確清除 uncertain、退休後晚到讀回、防清除後重播，以及 partial metadata omission／null／矛盾值不能解除 veto。另新增 production 雙 transport 測試：先建立四類 single callback、重連另一 synthetic peer 的相同 ID、晚到執行皆零 PUT 且 gallery／selection／latest／snapshot／error／busy 不變；同 owner 四類 single 正例仍成立。既有 App 測試只補必填 owner，保留原斷言。XcodeGen 以 `createIntermediateGroups: false` 對映兩份共用資源。

已編寫實際 UI journey：英文與繁體中文、大字級，素材多選選單 → 審閱 → 取消且讀回未變 → 再次明確確認 → 兩項結果及確切值讀回 → 清除記錄警告 → 取消仍保留 → 確認只清記錄且相機評分維持 5。in-flight 取消及 owner retirement 由 production App executor 測試覆蓋。

本環境沒有 Swift／Xcode：所有新 Swift compile、XCTest、SwiftUI／XCUITest 與 Darwin Foundation 實跑均為 **NOT_RUN**。上述 63 PASS 僅是 Python source／fixture／wiring 證據，不能替代 native acceptance。沒有實體 Canon／iPhone／Wi-Fi／USB 證據。

## 審查修正封存

- `07:50` 的 `15070d2b…` 完整 patch、收據與 checksum array 另存 `review-0750-*`；single-owner 修正的新 log 使用 `single-owner-*`。
- 原 `07:32` 收據、完整 patch、source checksum array 以 `review-0732-*` 另存，並保留更早 `review-0731-*` 及所有失敗紀錄。
- 獨立審查 P2（無界歷史、partial response 抹除 veto、single callback owner 漂移）修正後重新跑 source／全量 Python／Ruff；新記錄使用 `p2-*` log，最終 `receipt.json` 與 `source-checksums.json` 對應相同來源。
- 既有 `DesktopBridgeMediaFolderTests.testInfoAndMutationUseFreshPairedProvenanceAndDoNotCarryMissingFieldsForward` 未改寫或弱化；其 native 執行同樣 NOT_RUN。

## Release Assessment

- 最新已核發布基線：`v0.12.0` Development Preview，協調者於 2026-10-15 03:54:34 UTC 核對 GitHub。checkout／changelog 的 `0.13.0` 不等於已發布。
- 建議影響：整體 **minor**，因新增使用者可操作的多選批次能力；精確讀回、嚴格型別及 owner／派送修復單獨屬於 **patch**。
- 本批次不改版本、不提出自動發版、不宣稱 stable。
- 未解驗收阻擋：Apple native compile／XCTest／UI 實跑、精確新 SHA CI 與跨端整合審核均未完成。不能稱為 PR ready、main accepted 或 released。
- 實機狀態：NOT_RUN；不得以 synthetic fixture 或 source pass 宣稱相機可靠度／相容性。
