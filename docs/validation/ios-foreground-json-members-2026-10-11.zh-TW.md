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

## 首次必要 CI 與相容性控制修正

Head `9a4b8df864138376de2c76b36783bf504d38adef`、CI `38136280671` 的 Core job `114460721491` 已實際編譯並執行 318 項，317 pass／1 failure。duplicate、escaped／nested、512／513 深度及 client 停止請求控制均通過。唯一失敗為 Unicode encoding loop 的合法資料期待，Foundation 拋 NSCocoaErrorDomain／3840；原 loop 沒有 case 標識，因此尚不能確定哪個 encoding／BOM 組合，不能宣稱 double BOM 或 scanner 根因。

原 production 契約是先使用既有 Foundation 的語法／encoding 接受範圍，再額外拒絕重複鍵。測試候選保留全部十組資料，增加固定 byte prefix／encoding／BOM 標識；scanner 對十組合法資料都必須成功，對十組 duplicate 都必須以 invalidInventory 拒絕。完整 decoder 的 ordinary 與 strict 結果分別捕捉，再雙向比較接受／拒絕，拒絕時也比對 NSError domain/code；不能以包住兩者的 catch 吞掉 strict 錯誤。既有 UTF-8 無 BOM 完整 decoder 正例保持無條件成功。

這是收斂相容性測試契約，不改 production parser、不刪除失敗 encoding、不加入 permissive fallback。新增逐 case 診斷只記合成資料前 16 bytes 與錯誤 domain/code。修正候選本地 Swift runtime 仍 NOT_RUN；原失敗保留，等待本 run 全部 job 終態後才交同分支下一完整必要驗證。

官方參考：[CFString 外部表示與 BOM](https://developer.apple.com/documentation/corefoundation/cfstringcreateexternalrepresentation(_:_:_:_:))明示顯式 UTF16/32 BE/LE 不自動加 BOM；[Swift corelibs JSONSerialization](https://github.com/swiftlang/swift-corelibs-foundation/blob/main/Sources/Foundation/JSONSerialization.swift)記載五種 encoding／BOM 支援。這些不代替此 run 的 Darwin macOS 15.7.9／Swift 6.1.2 實際接受結果。
# 同輪 UI 導航失敗及有界候選

原 `38136280671` 已完整 failure：Core 317/318、App 344/344、UI 34/35。UI 失敗在日期／資料夾旅程返回 `media-folder-filter` 的第 879 行，不可歸因於 JSON production 改動。兩次 cache 都 `geometry-matches=true`／`reuse=true`，因此本次證據否定繼續調 cache tolerance 的方向。

實際 viewport 為 y=180.667、height=693.333，target 起於 y=-2164.333。兩次 649.333pt、750px/s 的 clipped target-seek 分別移動 851／861.333pt，最後 target y=-452；兩次手勢加 AX 已耗 8.456 秒。8 秒是新動作准入截止，並非同步 XCTest gesture 的硬 wall-time 保證。完整顯示 target 約需總位移 2361–2901pt，原兩次只有 1712.333pt。

候選只在已知有限 target 距離超過 1.5 個 viewport 時，使用官方 `XCUIGestureVelocity(rawValue: 1500)`，每次 helper 最多兩次。未知 lazy target、cameraActions、近距離 correction 的原速度／hold、clipped 端點、8 秒准入及所有業務斷言保留。每次重讀 target；近距離過頭沿原停止手勢反向修正，frame 消失僅沿既有一次 0.8 viewport 反向回收，仍找不到就失敗。

649.333pt 觸控段的理論時間由 0.866 降至 0.433 秒，但 XCTest event／idle、慣性位移不可由此換算。需要比原兩次多約38%位移只是本次 geometry 的最低需求，不是候選提供的保證。1.5 viewport 只控制速度，不改原0.8 viewport的seek／停止修正分界；介於兩者仍沿原750px/s、零hold seek。因此不預測固定手勢數，首滑若不足約1802pt，剩餘距離仍超過一次完整停止修正。純 helper 測試覆蓋雙向距離臨界值、有限 frame、次數上限、原模式不加速、實際位置及近距離過頭；尚未本地執行 Swift，也不能由策略測試宣稱真實手勢通過。

官方 API：https://developer.apple.com/documentation/xcuiautomation/xcuigesturevelocity

## df6dc77 終態與必要失敗取證

CI `38138774161` 完整 failure；Core318/318、App344/344、UI35/36，其他適用 gates 成功。實際 encoding trace 確定 UTF-32LE＋單 BOM（prefix FF FE 00 00）在該 macOS Foundation 的 ordinary 與 strict 都拒絕 NSCocoaErrorDomain/3840，其他9組都接受；scanner10組固定正負控制通過。原失敗未標case的限制仍保留，不把新資料追認為原run已記錄的case。

日期／資料夾雙語旅程12:41:19實際通過，遠距helper幾何控制也通過；成功路徑未輸出位移，因此不能宣稱已量測1500的慣性或每次必成功。唯一失敗改為 canonical shutter AF旅程的繁中段：拍攝、新JPEG預覽及原檔ready/share控制通過，close preview／media back後更多操作按鈕未在原8秒wait內可互動。該步不經generic加速helper，不因此判定兩者有因果。

Artifact `11702771829` 已下載核對179540501bytes、SHA256 `cbdbe3db44d255b4accd637d819b12faebc943716cee4613f3622a61faf3a842`。ZIP2114項無絕對／上行／symlink檔名。xcresult只有more-actions query chain、沒有匹配元素frame或完整AX；最後PNG為12:32:52預覽分享ready，已實看，但不是失敗瞬間。back tap完成後exists查詢2.60秒、下一Find4.03秒，不能單憑耗時確定環境慢、頁面未返回或遮擋。

診斷候選保持wait8秒與原short-circuit exists→enabled→hittable，只在原讀取前後記stage、結果及elapsed。最多16個80字stage、160字caller context，總count保留；不額外讀frame、identifier、root或debugDescription。失敗先print並保存文字attachment，再至多一次app screenshot；原interactive結果固定，隨後原XCTUnwrap必失敗，不能由晚到snapshot變pass。root/frame明記NOT_OBSERVED。純trace控制驗容量與長度，Swift此新候選尚未本地執行。

文字容量與截圖呼叫次數有界，但app.screenshot是同步IPC，XCTest無可用per-call timeout/cancel；取證只能受原job外層timeout，不承諾幾秒必返，不建立background假取消。若同步呼叫長掛可能尚未到原assert，先前文字及原wait失敗已保存，不能當pass。無新產品修改、timeout增加或自動重跑。
