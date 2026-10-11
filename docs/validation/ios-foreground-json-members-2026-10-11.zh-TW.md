# iOS 前景交付 JSON 完整性

## 原 P2 契約與來源反例

前景自動交付必須先取得完整清單與新讀取的有效大小。既有 Bridge strict reader 使用 duplicate-rejecting object_pairs_hook；其 test_partial_and_ambiguous_json_responses_cannot_prove_exhaustion 已拒絕同物件重複 pagenumber/path。iOS foregroundJSON 原先沿用一般 JSONSerialization dictionary 解碼，重複成員的原始證據在後續型別檢查前便不再可得。

例如計數回應 `{"pagenumber":2,"pagenumber":0}` 或清單回應 `{"path":[null],"path":[]}`。若解碼保留後者，便可能被接受為完整空清單；這種有歧義資料不能作為前景 baseline 或 fresh-size 證據。本地沒有 Swift runtime，沒有聲稱已執行 prepatch 紅例，也不保證所有 Foundation 版本選擇相同重複值。

## 最小修正

只在 foregroundJSON 使用新 internal decoder。原一般 decodeJSONObject、公開 API、手動讀取 fallback、HTTP 請求、2 MiB 回應上限與原時間預算不改。

Foundation 仍先驗證完整 JSON 語法與 object 根節點。獨立結構掃描原始文字，在信任已轉換的 dictionary 前，檢查每一層 object 的原始成員；每個 key token 再由 Foundation 解碼，因此 escaped 相同鍵、surrogate pair、nested object 與 array 內重複成員均不可被吞掉。不同 object 可各自使用同一名稱；字串值內的括號、逗號與看似 JSON 的內容不會被當成成員。不是 regex JSON parser。

Swift String dictionary 也會將 canonical-equivalent Unicode 鍵視為相同；strict 路徑保守拒絕這類別名，以免型別轉換失去一個成員。媒體 ID/value 的原始 Unicode 身分不做 normalization。候選保留 UTF-8、UTF-16、UTF-32 與 BOM 的有效 JSON 解碼，掃描深度上限 512（不保證 Foundation 接受至該邊界）；不新增可選 permissive fallback。

## 控制與目前證據

- 10 個重複鍵資料，包括 pagenumber/path/filesize、escaped 字母／反斜線、nested/array、surrogate 與 canonical-equivalent key。
- 合法 sibling 同名、空 object、字串內偽 JSON、一般數字／布林/null、UTF 編碼含／不含 BOM；malformed JSON 與非 object 根節點仍拒絕。
- 新增 完整 decoder 路徑的 32 層合法／重複成員與 513 層拒絕控制。另直接對 internal scanner 建構合法 JSON，驗 depth512 正例、depth513 負例及深巢 duplicate；這讓 Foundation 先拒絕深度不會遮住 scanner 邊界。全部 Swift runtime 尚待執行。
- 真 CCAPIClient fixture 要求 ambiguous count 不再請求 unpaged/page、ambiguous page 不回 prefix、ambiguous fresh size 不回 listed size；只保留已發出的 GET，不增加相機操作。
- App 接線 source review 核對 CameraAppState→CameraSession→strict client；inventory throw 進入 controller catch，freshInfo/prepare 不可達，沒有 manual fallback。這是來源路徑核對，Core request assertion 不是完整 App 端到端執行。
- 一般手動 decoder 的重複成員與空回應相容行為保留，不固定哪個 duplicate value 勝出。
- 本地只完成 source diff 檢查及 Python 對 10 個 Swift fixture 原始 JSON 的語法／重複鍵核對；這不是 Swift parser 執行。
- Swift 編譯、Core XCTest、完整 App／真相機驗收均 **NOT_RUN**。本地完整來源 review 已完成；沿原 PR236 一次交付正常 CI，以新 exact head 的實際結果判定，不沿用 parent f2f6b9a7 的綠燈。完整 App 人工集中驗收仍後置，不以來源審查宣稱 runtime 修正成立。
