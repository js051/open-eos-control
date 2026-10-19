# Apple Actions 驗證紀錄

此驗證只補充 Apple 平台編譯及合成測試；不代表 `ci-complete`、真相機驗收或發布完成。

## 首輪

- Source：`18aee283ea6352688da45a0a3579e1f2c54f6b24`
- Run：https://github.com/js051/open-eos-control/actions/runs/39184360780
- `secret-scan` 的完整歷史掃描、版本、驗收工具、CI/release helpers、Camera Import contract 及 actionlint 均通過。
- macOS 15.7.9 / Apple Swift 6.1.2：OpenEOSCore production module 編譯完成，測試 module 編譯失敗，尚未執行測試。
- 唯一根因：`MagnificationPeer` 未實作 `CameraHTTPTransport.download(_:)`。補上明確拋出 `URLError(.unsupportedURL)` 的 fake method；此 fixture 不支援下載，不新增 production fallback，也不改原放大／座標測試斷言。
- 失敗證據：https://github.com/js051/open-eos-control/actions/runs/39184360780/job/117843609675

- iOS App 的 unsigned Simulator build 與 bundled resources 檢查通過，fake camera 與 iPhone Simulator 啟動成功；App/UI 測試 target 編譯失敗，測試尚未執行。
- 兩個測試 fixture 的陣列型別推導為 `[Any]`：RTP packet header 與 LUT RGBA bytes。補明確 `[UInt8]` 型別，不改任何 fixture byte 或測試斷言。
- App 失敗證據：https://github.com/js051/open-eos-control/actions/runs/39184360780/job/117843609688

修正後結果待後續 Actions 執行，不以本地源碼檢查當作 Apple 測試通過。

## 第二輪核心執行

- Source：`64eb0680aaa15b29fdf5edc168b207d79c452fce`
- Run：https://github.com/js051/open-eos-control/actions/runs/39185127429
- Core 的測試 module 已成功編譯並實際執行 665 個案例；11 個案例共 19 個失敗斷言（7 個 unexpected error）。不是 19 個失敗案例。
- 三個 Bridge session replacement 案例得到 `sessionChanged`，另行核對嚴格 session ownership 與 fixture 的契約。
- 八個 URLSession 案例涵蓋 capture configuration、maintenance、recording、shooting settings 的 GET redirect／取消／deadline：Apple URLSession 對 GET streamed upload task 回傳 `NSURLErrorDomain -1103`，peer 未收到請求。
- 修正 GET 使用同一個 strict session/delegate 的 data task；寫入仍採單次 body streamed upload task。GET 非空 body 於派送前拒絕；新增四個負例。未放寬 redirect/auth、回應大小、完整 EOF、timeout 或取消規則。
- 原失敗 log：https://github.com/js051/open-eos-control/actions/runs/39185127429/job/117845912674
- 此修正目前只通過 Linux Swift 語法解析，仍需後續 Apple 執行證據。
- Bridge 三案確認是舊成功-close fixture 回傳 `200` 加 JSON，而既有契約要求 `204` 空 body；只修各 exact DELETE session endpoint，並加上 close 確認斷言。production 的未確認退場禁止重開防護不變。
- App 第二輪仍止於 test target 編譯：`ConnectionCancellationTests` 五處區域變數從 implicitly-unwrapped optional 屬性推導成 optional。改為 `try XCTUnwrap` 明確驗證 fixture 已建立；未變更流程與原斷言。
- App 失敗 log：https://github.com/js051/open-eos-control/actions/runs/39185127429/job/117845912595

## 第三輪核心通過

- Source：`7136f9cb2b669e2f3716e29d00d47fd60b47cf55`
- Run：https://github.com/js051/open-eos-control/actions/runs/39185931674
- 2026-10-19 01:37:01 UTC，macOS Apple Swift 實際執行 **669 / 669**，0 failures / 0 unexpected，約 90 秒。
- 原 11 個失敗案例與新增四個 GET 非空 body 負例均通過。Bridge 的 strict retirement guard 未放寬；四個 HTTP task 的 GET 真 loopback、redirect 拒絕與取消／deadline 已有原生 URLSession 執行證據。
- App 隨後完成 798 個案例，其中 60 個案例共 197 個失敗斷言；UI 完成 43 個案例，其中 10 個案例共 11 個失敗斷言。原生編譯缺陷已解，但不能把此輪稱為 Apple 全綠。
- App 原始結果：https://github.com/js051/open-eos-control/actions/runs/39185931674/job/117848339987
- 新失敗涵蓋舊 Bridge close fixture、LUT GPU 數值、JPEG 解碼、媒體／錄影流程、可見性與 OS picker；按根因逐批修正，不放寬原測試門檻。
- 下一批已核對九個 fixture 的 exact session DELETE 成功分支，依現有契約回傳 `204` 空 body；既有延遲 gate 與失敗分支保留。
- Stop 嘗試返回拒絕或仍錄影時，media review 不應殘留 `capturing`。重設顯示為 idle，但保留停止責任、review 邊界與 recovery，仍只有 fresh false observation 能啟動 review。
- Held AF 的 UIKit 初始控制設為 disabled，直到第一次能力更新；語言測試等待第一次已套用的 label/disabled 狀態。
- Apple-only workflow 增加既有 bounded recovery evidence exporter。只輸出三個指定合成 UI tests 的 allowlisted PNG／幾何摘要，最多 24 MiB；診斷失敗獨立保留警告，不修改原 test 結果或 `ci-complete`。
- 尚無真相機、USB 實機或三端完整對齊完成聲明。

## 第四輪待驗修正

- Core Image 的 extended-range cube 節點在插值前被限制範圍，原 shared／RTP 真 GPU 斷言揭露此差異。渲染表改以逐通道 affine 編碼到 `[0,1]`，插值後還原，再沿原最終 clamp；CPU 原始節點與既有 in-range bytes 不改。
- 補常數極值、透明 alpha、不可表示 range 的 fail-closed 與原表 bytes 測試。保留原 PNG／JPEG／Metal／H264 oracle 和容差；本地數學對照不是 GPU 通過證據。
- `.cube` parser 正規化 CRLF 行界線，補 CRLF／mixed-LF 等價及 bare-CR 拒絕測試，原 bytes decoder 契約不改。
- 補 hosted Held AF 同一 UIKit 控制的未連線、能力可用、背景、前景、斷線 enabled 轉換；斷言渲染能力沒有合成 autofocus mutation。
- 此批僅完成源碼審查與 Linux 語法解析，Apple 全套結果仍待後續 Actions。

- 第四輪 source `da26c6840a1fe3184d438a6b8e54873c859cea10`，run https://github.com/js051/open-eos-control/actions/runs/39189147670 在 Python source guard 結束：219 項中 1 項仍要求舊 raw cube/direct-clamp 字串，Apple jobs 尚未執行。
- guard 更新為檢查原表保留、in-range bytes、affine encode、cube 後 restore／alpha 不變、最後 clamp 的順序與 finite 拒絕。既有真 GPU 測試名字／oracle 保留。本地完整 219 項重新執行通過，仍需重啟 Apple gate。

## 第五輪與後續修正準備

- Source `6696e5eef4cd70c3a32cb91734cfb27e33db7783`，run https://github.com/js051/open-eos-control/actions/runs/39189586059 已通過完整 secret-scan job；Apple Core 於 02:25:18 UTC 實跑 669 / 669，0 failures。App build/resource checks 通過；02:48 UTC terminal，App 805 個案例中 20 個案例共 92 個失敗斷言，UI 43 個案例中 10 個案例共 11 個失敗斷言。
- 第三輪 ImageIO 確實接受四個 premature-entropy EOI 檔。新增 decode-admission 的 sequential Huffman／MCU 完整性檢查，原 marker 結構 API 與 71 個固定向量／hash 不變。依 ITU-T T.81 B.2.1、E.2.3–5、F.2.2/F.2.3，驗 DC amplitude、AC run/EOB/ZRL、stuffing、restart 間隔與 padding；沿原輸入／尺寸上限，每 MCU 可取消，不配置像素陣列。
- JPEG 本地真 Swift 證據：71 個原結構結果一致；15 個結構有效檔中 11 個正向通過、四個截斷拒絕；七個新 direct XCTest 方法通過，含 odd-dimension interleaved padded MCU 及分開 component scans。原生 ImageIO 仍需下輪；progressive 仍是原結構加原生解碼路徑，不宣稱完整 progressive entropy 驗真。
- 測試契約修正：ACK Stop 無 observation 的 duty 依原 shared lifecycle 向量為 false，readback recovery 仍保留；RTP 的假時鐘維持 5 秒，等待已通過現行 epoch guard 的 stalled 發布，避免讀到 watchdog 尚未發布或舊 epoch 的事件。
- Media fake metadata 保留 Bridge folder identity，simulator 不虛構未宣告的 folder；兩個 listing fake 遵守原請求 limit，原超量拒絕與 60-item／1000-row 斷言不改。

- 第五輪真 Apple：CubeLutImportRead 7/7、CubeLutNumericContract 8/8、RTPNativeLutRendering 12/12 全過，原 PNG／JPEG／Metal／H264 oracle 與容差未變。LUT 數值及 CRLF 修正已取得原生證據。
- Manual clear 修正以實際 store epoch／clear 成功與同批次 row UUID 判斷工作清單是否失去持久保存；不讓晚回覆蓋掉已完成批次警告，也不讓提交前失敗虛構 worklist 失效。補失敗清除仍保留 plan／ticket 並完成原始檔的負例。
- 前連線警告 UI fixture 改成舊 Stop 失敗但 DELETE 204 已確認退役，再明確啟動新連線的 owned shutter；另留 DELETE 502 禁止重連負例。strict retirement guard 不變。
- Bounded UI artifact 共 17 個 allowlisted 檔、2,069,730 bytes；Stop／Disconnect 幾何無重疊，仍有 unknown／重連旅程失敗待修，不能用診斷成功當 UI 全過。

## 第六輪交付與下一批待驗

- 第六輪 source `cd832197c5f10a7ea3a02690ed0de72e056e764a`，run https://github.com/js051/open-eos-control/actions/runs/39193227320 。原 hooks 通過且 dev exact SHA 讀回；Core 於 03:11:27 UTC 實跑 669 / 669 通過，App build 通過；App test 編譯在 MediaResponseOwnershipTests 的 Optional.map 產生巢狀 optional 而失敗，App/UI 尚未執行。下一批改 flatMap 保留 failable folder initializer，不 force unwrap。
- 下一批針對第五輪實際失敗：三個 Live View peer 的 advertised JPEG endpoint 原本回 404／假 JPEG／JSON，改為既有 `rgb-baseline` 真向量。前景測試依既有產品契約先證沒有自動 frame／focus read，再明確 Start；原取消與 Stop 斷言保留。
- Control ownership fixture 分開 generic session DELETE 與 maintenance-owned DELETE，精確驗原 session 一次、replacement 零次；影像等待同時等原資料與完整 frame timestamp；Bridge setting 的有效 unavailable readback receipt 仍驗不可消耗 draft、可明確 recover、單次 mutation，不誤當 transport exception。
- Held AF 的 UIKit control 增加持久能力及 SwiftUI environment 上限，外部 enable 不能越過相機禁用。保留第五輪初始 disabled 真紅與 hosted 正向轉換，補外部 enable／disable、父層 disabled、顯式原 owner release 測試。此候選尚未經 UIKit 執行，不宣稱自動 touch cancellation。
- Upload journey fake 僅對其已建立 exact session DELETE 回 204 空 body，未知／已退役／子路徑仍拒絕；補真 Bridge client close/reopen 及錯誤 DELETE 負例。OS picker 真選檔仍待原生驗證，未變更產品 Documents 可見性或注入 picker delegate。
- 原 bounded exporter 增加四個既有合成 media UI 案例，仍固定 test／phase／control allowlist、總 24 MiB、PNG signature、大小與 symlink 限制。先凍結原 wait 結果再保存證據，斷言與 deadline 不變；47 個 Python CI helper 測試通過，原 source guard 保留並增加新 AF 負例存在檢查。

## 第七輪原生結果與第八輪修正

- 第七輪 source `55ac5a18b9c7e14e0332af1c29d5adb9b40eb2cb`，run https://github.com/js051/open-eos-control/actions/runs/39194979760 。Core 669/669 通過；App 819 個案例中 3 個失敗案例／5 個斷言，UI 43 個案例中 10 個失敗案例／11 個斷言。原生 App build 與所有 test 編譯通過。
- JPEGOriginalContractTests 15/15、LiveViewJPEGDecode 3/3、ControlResponseOwnership 26/26、MediaBatchDelivery 68/68、RecordingReadbackRecovery 37/37、ShortShutterRecoveryState 17/17 通過；Held AF hosted disabled/能力/父層 disabled/顯式 release 已有真 UIKit 證據。JPEG 仍不宣稱完整 progressive entropy 或真相機驗收。
- App 新真失敗揭露 pending Start 的 compensation 已失敗且 task 已清空時，稍晚的 background retirement 又重送 Stop。修正先等原 Start 結算，再保留其 failed Stop debt；同型 restart 只在 captured Start 的失敗結算時停止自動續作，原明確 retry 保留。新增確定性 gate 排程及成功清理正例；預設 nil gate 不增加產品等待，Apple 執行仍待下輪。
- Foreground fixture 的三處成功序列補上原 Start 必須取得的一張 exact flip 首幀；完整 method/path/foreground 序列更嚴格，held ownership 期間零請求斷言不變。
- 新 bounded 圖證明短 landscape Connection 的 scroll AX frame 延伸到固定 warning 下方，改用實際 sibling 空間。日期最大字級 compact picker 把文字擠成直條，改為獨立完整標籤行；Apply 的 owner/range 拒絕不變，Cancel 後測試先等舊 controls 消失再要求唯一可操作 Apply。
- Metadata 的有界命令 header 改 eager，歷史 rows 保持 lazy；離線格／刪除測試實際捲到原 JPG 再驗證，不能因下方 lazy cell 尚未建立就判不存在。
- Canon direct 沒有已核實 REC GET，原錄影 UI 的 ACK 不可冒稱 fresh false。保留 Canon unavailable／零 review／單 REC/STOP 負例，完整正向 review／原片準備改用原 simulator-status 模式，不能把此結果報成 Canon 真錄影 readback。
- 同一 bounded artifact 增加既有 direct-controls 白平衡失敗畫面與固定幾何，保留原 count==1／狀態／deadline 失敗；仍總 24 MiB、固定 8 tests／5 media phases、PNG/text limits 與 symlink 檢查。48 個 CI helpers 本地通過。OS Files provider 可見性仍未更改，真 picker 選檔門檻保留。

## 第八輪原生結果與第九輪候選

- 第八輪 source `dc533b858da09eec7e4174867ae53f252cadbfed`，run https://github.com/js051/open-eos-control/actions/runs/39198652632 。Core、secret scan、App build 通過；2026-10-19 04:28 UTC App **821/821** 全過，包括 pending Start 結算後不得自動重送 failed Stop 的確定性雙 caller 回歸。UI 43 個案例仍有 6 個失敗案例／8 個失敗斷言，未達完整 Apple 驗收。
- UI recovery landscape／重連、離線格與刪除旅程已通過。剩餘為日期 Apply、metadata 子選單、白平衡點選、繁中錄影模式切換，以及兩個原 OS picker 旅程。
- 日期改用單一 identifiable presentation 保存開啟時 owner／初始日期，sheet 內持有 local draft；舊 presentation 的 Cancel／Apply 不能消耗新 opening，Apply 仍需原 session owner 與 setter 成功。補四個 owner／日期 model 測試；保留唯一且 enabled Apply 的原 UI 門檻。重複 AX 節點本身不能證明 owner 為 nil，修正仍待真 UI 執行。
- 白平衡失敗圖證明原 surface 比例點在 decoded image 下方；改用真正 image frame 的比例點，保留單次 mutation、精確狀態與原 deadline。metadata 每次子選單先等待實際互動；錄影切換先驗 Video 已選定及 Apply 可互動，原 movie-mode/count 斷言不變。錄影失敗僅能確證 canonical 繁中沒有送出模式寫入，UI 同步候選尚待 Apple。
- 固定診斷新增 capture-mode picker／Apply 幾何，不收未知 label 或 URL，原 test/phase allowlist、24 MiB 與 PNG/text/symlink 限制不變。
- OS picker 使用 fresh CI runner 的合成 Documents：僅 test xcodebuild 指定 application-target 專用 plist 變數與獨立 `.derived-data-ui`；正常 build／Release 預設仍指原 `App/Info.plist`。原檔未新增分享設定。測試 plist 由 exclusive create 產生並保存 hash；always verifier 檢查原檔未變、正常 app 未曝光 Documents、test app 才啟用兩個可見性 keys 且 bundle ID 相符。保留原 native picker 和取消／選檔負例，無 delegate 注入。
- Linux 完整 219 個 source guards 與 57 個 CI helper tests 通過，三個修改 Swift 檔語法 parse 通過。日期 source guard 的舊 assignment 字串失敗已保留，更新為 presentation identity／owner／literal carrier／local draft 必要檢查，原 DST／時區與 session 防護檢查不變。這些結果不替代第九輪 Xcode typecheck、UIKit 或 OS picker 執行。

## 第九輪建置結果與第十輪窄修

- Source `a260dedbf2954c0d550e0bcd9cc58d16cde88023`，run https://github.com/js051/open-eos-control/actions/runs/39202203898 。secret-scan 與 Core 通過；普通 App build 在 05:00 UTC 回報 Info.plist 重複產物，App/UI tests 尚未執行。
- XcodeGen 對間接 `INFOPLIST_FILE` 輸入未自動排除原 App/Info.plist，造成 Copy Bundle Resources 與 ProcessInfoPlistFile 同時產生 app Info.plist。App sources 明確排除該單一 plist 的 resource copy，仍由原 ProcessInfoPlist 處理；未刪除檔案、改 keys 或放寬隔離驗證。新增 source guard，57 CI helper tests 通過；仍待 Xcode 真建置。
- always 隔離 verifier 因前置 build 失敗、test plist 尚未建立而一併失敗，保留此結果，未以跳過 verifier 掩蓋未驗證狀態。

## 第十輪結果與第十一輪有界補證

- Source `e9881e174954ae7f74584c2ed0bac7cfb9e5745a`，run https://github.com/js051/open-eos-control/actions/runs/39202760803 。普通 App build／資源、Core、安全及 test plist 隔離驗證皆通過；App **825/825** 通過，含四個新日期 owner/model 案，日期英繁中 UI 旅程亦通過。UI 43 案仍有 **5 個失敗案例／5 個斷言**。
- 白平衡的原單次 count／click 狀態已通過，後續設定結果 sheet 的「開始新設定」互動失敗；不把它誤報為原白平衡仍失敗。錄影 canonical 繁中在單次 Video tap 後仍為 Photo draft，尚未到 Apply／寫入。metadata 第一輪英語 XXXL 在 submenu 無有效 activation frame 時，isHittable 查詢本身失敗。
- metadata 僅該兩個 native menu 的等待先核 exists／finite 非空 frame，再核 enabled／hittable，維持原 5 秒與業務斷言；不是放寬全域 helper。若仍失敗，沿既有固定 phase 收圖。錄影補單次 tap 前後固定兩 segment 幾何／selected；設定結果 sheet 補原失敗後固定控件幾何，皆不重 tap、不改相機狀態。
- OS picker 英文 Cancel 與重連再取消已通過，繁中查找「取消」失敗；選檔 case 仍找不到 synthetic cell。隔離 plist 真驗過不代表 provider 選檔完成。增加這兩個原合成案例的 exact test／phase allowlist、八個固定 OS／synthetic 查詢計數及圖；不輸出任意 label、URL、路徑或全 AX。
- 小型 artifact `12165039456`：4,696,566 bytes，SHA256 `c695b647cb747158898da18cf40ef0fe3c943e06759d3e5ca11e09815bc74e36`，保留第十輪失敗圖。第十一輪 58 個 CI helper tests、Swift syntax parse 與 diff 檢查通過；其餘缺陷仍待原 Apple 流程，沒有把補證當完成。

## 第十一輪結果與第十二輪窄修

- Source `aaf04e5c7c7e5b166b4f27a7949e30a8b898c995`，run https://github.com/js051/open-eos-control/actions/runs/39206551921 。Core、安全、普通 App 與隔離 verifier 通過；App **825/825**，UI 43 案有 **4 個失敗案例／4 個斷言**。完整錄影雙模式雙語旅程這次通過，但新增 tap 前診斷會影響調度時間，尚不能排除觀察者效應或宣稱已確證 picker 根因修復。
- 白平衡原單次寫入／狀態已過；失敗圖確認 More settings 正確開啟且新設定按鈕可見。AX 中父層 outcome ID 出現在兩個子按鈕而專用 new ID 消失。原 panel 增加 explicit accessibility containment，保留各子按鈕 ID、原 guards／動作；原 UI helper 增加 new／refresh 各唯一 ID 斷言，待 Apple 真紅綠。
- OS picker 選檔失敗圖顯示 Documents root 的 Synthetic Upload Journey 資料夾。fixture 現在先辨識已在檔案位置或該 exact 合成資料夾，必要時明確導航再沿原選檔／取消／不確定性斷言。另一 case 的診斷因動態 AX index 的 frame 查詢而中止；改為先保存圖、只記八個固定 query 計數，不遍歷動態元素，保留原取消失敗證據與 deadline。
- metadata finite-frame 等待後仍在 isHittable 取 activation point 時拋錯。只在第一次已存在且 finite 非空 frame 的原固定 phase 先保存 popup，然後仍執行原 enabled／hittable 門檻；未改產品 menu，也未將查詢失敗視為成功。artifact 原 24 MiB／固定 allowlist／檔數上限不變。
- 第十一輪 bounded artifact `12166942008`，5,575,659 bytes，SHA256 `e3a6a12bb07f57ecedd0d69d453b77274748ec949c4c66ae7c730701b81b3085`。本批 Swift parse、CI helper tests 與 diff 檢查通過；UIKit containment、OS provider 導航與 metadata popup 仍待第十二輪真 Apple 驗證。

## 第十二輪 source guard 與第十三輪修復

- 第十二輪 source `341fece7e7b951a9ea8aab71374ab5a4f7775f9f`，run https://github.com/js051/open-eos-control/actions/runs/39210846870 在 source guard 結束：219 項中 1 項要求的 `No delegate injection` 註解隨導航說明改寫而消失。Apple Core／App 未啟動，沒有新的原生結果。
- 恢復仍符合實作的原註解，完整 219 個 source guards 真執行通過；未修改 guard 或原生測試行為，保留第十二輪失敗證據。

## 第十三輪結果與第十四輪 native UI harness 修正

- Source `40558c71138a81ced73e96e6ebe74181d8b86b1d`，run https://github.com/js051/open-eos-control/actions/runs/39211349203 。安全、Core、普通 build 與隔離驗證通過，App **825/825**；UI 43 案仍有 **4 案／4 斷言失敗**。白平衡設定結果的 new／refresh 專用按鈕 ID、唯一性與後續旅程真紅轉綠。雙語雙模式錄影再次通過，但仍帶原診斷，不把它當無觀察者效應的證據。
- metadata popup 圖確認 Rating 可見，原 identifier proxy 卻無有效 frame。fixture 以精確本地化 Rating／評分標題解析唯一 native button，不挑 firstMatch 或忽略重複；原 5 秒、有效 frame、enabled／hittable、實際 tap、取消零寫入與確認評分斷言保留。
- 日期 Apply／重開／rating／known-folder Apply 已過，最後由照片區回到 folder 控制的 8 秒導航僅完成兩次長距離 seek，目標距可見邊界仍差約 156pt。只對此已確認屬 MediaView 內容的回返呼叫，延後離屏 enabled／hittable 查詢到 fresh surface／target frame 已相交；首輪快捷、無容器 fallback、原最後完整互動檢查、8 秒及手勢計畫不變。其他所有呼叫維持原路徑；此為減少已證昂貴 AX round trips 的候選，仍待真執行。
- 原生 picker host view 先存在，而 OS provider 仍可為空白；第十三輪 screenshot-first 保存了此狀態，幾秒後固定 query 才見真控制。增加獨立 30 秒 provider 啟動上限，整段旅程因此可多等待 30 秒，要求 exact Cancel／Browse 各一；之後仍執行原語言 Cancel、8 秒互動／選檔及 5 秒 dismiss，所有 mutation／不確定性／重連斷言不變。不把 provider 啟動當上傳成功，也無 delegate 注入。Apple 官方 directoryURL 僅是嘗試採用的起點：https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller/directoryurl 。
- 第十三輪 artifact `12169515826`，6,388,855 bytes，SHA256 `3f9772493da9d9185f24b7d5bb9760143f2e0bf65056fa0773b03df1a5b644b9`。本批完整 219 source guards、58 CI helpers、Swift parse／diffcheck 通過，尚待下一輪 Apple，沒有放寬影像 oracle 或相機 deadline。

## 第十四輪結果與第十五輪有界補證

- Source `4e3aaacdfc98337749f223a123e0004f8d5f3c6f`，run https://github.com/js051/open-eos-control/actions/runs/39216067619 。安全／Core／建置及隔離驗證通過；App **825/825**，UI 43 案剩 **3 案／3 斷言失敗**。日期完整雙語／XXXL、資料夾往返已通過，WB 與雙模式錄影維持通過。
- metadata 的 localized query 仍解析原 native Rating 節點；第一個 finite frame 後 isHittable 記錄 activation-point 錯誤，後一輪 poll 卻可通過，說明第一個有限 frame 不足代表可互動。候選在原 5 秒內要求兩次穩定 frame，再查原 enabled／hittable；消失或 invalid 會重置，沒有額外 sleep、延時或座標代點。第一個已讀 frame 以固定欄位保存，仍待 Apple 驗證，不把候選當已證根因。
- OS provider 啟動 gate 本輪通過。選檔 case 對 folder StaticText 的真 tap 後仍停在 parent；候選改點唯一含該 exact 合成標籤的 Cell，保持原 8 秒與唯一性、真正 picker／delegate 路徑及後續全部斷言。Cancel 已有完整可見畫面卻未通過原互動 waiter，尚未確證是 hittable／enabled 結果或兩次 AX Find 耗完 8 秒；加入既有 trace 回呼只記固定查詢階段與 Bool，不增加 AX 查詢，不增加 timeout 或刪互動門檻。
- 診斷僅增加唯一 Cancel／folder Cell 的 frame，以及 menu 已讀 frame；不遍歷會變動的檔案文字、不再於失敗診斷呼叫 hittable。固定四種 frame 欄位只接受 index 0，未知 ID／文字／多 index 均拒絕；原 24 MiB、64 檔、PNG/text/symlink 防護不變。完整 219 source guards、59 CI helpers、Swift parse 與 diffcheck 過。
- 第十四輪 artifact `12171790417`，6,704,769 bytes，SHA256 `bfb175c67ca5a6c69f26615dca94db88c1ba6b49548d00195aebc11bd5dd7a73`。上述三個剩餘 UI gate 仍未完成，不宣稱 Apple-only 或三平台全綠。


## 第十五輪結果與選檔後置診斷

- Source `db0b9616dee72756dc7e1605d5b9d4f076e82f1a`，run https://github.com/js051/open-eos-control/actions/runs/39221563182 。安全、Core、普通／測試 build 與隔離驗證通過；App **825/825**，UI 43 案剩 **2 案／2 斷言失敗**。日期往返、WB、錄影以及真 OS picker 雙語取消／重開通過。
- 上傳旅程已完成英語 cancel／lost-ack／success 與繁中 cancel，包括原重連與歷史不確定性斷言。繁中 lost-ack 真點 synthetic file 後，在原 8 秒內未見 uncertain 結果；尚未到最後繁中 success，不能宣稱完整選檔／上傳驗收。沒有該失敗點畫面，尚不能分辨 picker 未交付、admission／file-access 拒絕或結果呈現。下一診斷先保存原等待 Bool，失敗才收固定 picker 畫面與 complete／uncertain／not-sent／recovery／error／cancel 六個計數，不讀錯誤文字，原失敗仍保留。
- metadata 真 Rating frame `(13,62,370,123)` 與螢幕完整可見列吻合，排除先前「已知 off-layout proxy」的肯定說法；localized query 並未找到另一節點。5 秒內只有一次有效 frame，下一次解析仍 retry，未到 enabled／hittable。這是原生 Menu AX 尚未穩定的證據，不能推論實體觸控失效，也不能把追加等待當已證產品修復。
- 第十五輪 artifact `12173931903`，7,775,005 bytes，SHA256 `ef98117769ac9fb99d74e39d4e0f395dc8385468bfc78fdb36491c1aae0b9f3e`。後置診斷仍沿原 exact test／phase、24 MiB、64 檔與 PNG/text/symlink 限制；尚無新的 Apple 執行結果。

### 第十六輪候選範圍

- 大字級 metadata 改以原媒體 ScrollView 內可展開的動作群組呈現，避免超大文字巢狀暫態選單；普通字級仍為原 Menu。所有 rating／protection／rotation／archive 選項、動態能力禁用、同一 frozen draft 確認與 owner 防護保留。這是可用性設計改善，並非已證明舊選單的人手點擊失效。
- 原 XXXL 雙語旅程新增未展開時不可見 mutation control 的負例，展開後真正捲到相同 Rating 與 rating 5 控制，仍要求原 5 秒內 enabled／hittable。原取消零寫入、確認後精確 readback／單次操作、歷史清除取消等斷言未更改；一般 Menu 分支與所有 target／能力 guard 增加 source guard，這不替代 Apple UI 執行。
- run15 上傳失敗末尾有 unexpected termination 收據，尚未有 crash 原因。後置診斷先讀固定 app state；非 running 狀態只記 enum 整數，不查已退出 App 的 AX／畫面。不得把缺少 uncertain 結果先判為 ticket 被取消或 file access 失敗。
- 本地完整 220 source guards、61 CI helper tests、兩個 Swift 檔 syntax parse 與 diff check 通過；沒有本地 UIKit runtime。第十六輪尚待交付與真 Apple gate。


## 第十六輪原生結果與第十七輪同步／導航預算

- Source `7c24b558a3a96b5f814f22629b307f2836c4d5d5`，run https://github.com/js051/open-eos-control/actions/runs/39228047526 。安全、Core、普通與測試 build／隔離驗證通過；App **825/825**。大字級 metadata 完整雙語展開、取消零寫入、確認評分／readback／歷史旅程通過；真 OS picker 雙語取消重開與六種語言／cancel／lost-ack／success 上傳重連組合全過。上傳這批僅增加失敗後診斷，前輪 unexpected termination 原因仍未確證，不能宣稱 crash 已修。
- UI 43 案有 **2 個失敗案例／3 個斷言**。direct simulator 控制已完成 Video、REC、Stop，最後 Photo 沒有第二次 mode 更新，count 仍 1；單 tap log 不證選段／Apply 已被接受。兩個 mode 轉換加入與既有錄影旅程一致的前置互動與 selected gate，各 5 秒有界、每個 segment／Apply 仍僅一次 tap；原20秒 mode 狀態／精確 count 1、2 不變。原失敗才收固定 direct-capture-mode phase，例外原樣重拋，無新成功判準或重送。新增前置等待會增加整段 journey 最長時間，不把它報成產品性能改善。
- 日期已知資料夾 Apply 與篩選值正確，XXXL 第一照片的 lazy card 在第三次原生手勢後才出現於 `(140,899.67,48,48)`，viewport 底端 874，仍需約74pt修正。三次手勢／idle 主導約9秒，既有sole-container cache正常；不是缺少照片或冗餘AX查詢。僅這個明確XXXL長距離呼叫將導航預算 **8→15秒**，容納第四次停止修正；正常尺寸與其他呼叫原預算不變，手勢速度、hittable／真tap、尺寸與所有日期／篩選斷言保留。這是根據量測調整harness導航上限，不是產品性能修復，也不改相機deadline。
- 第十六輪 bounded artifact `12177490765`，5,323,409 bytes，SHA256 `e27903cbb068840bc2b23df6ae9f121deb783e49043cb0ed1ec9ecf06ae34ec4`。新增direct phase只屬原direct測試，沿原固定控件／24MiB／128行／64檔／PNG／symlink規則，無任意文字或URL。第十七輪仍待原Apple驗證。


## 第十七輪結果與第十八輪固定模式控制位置

- Source `ca4eefd25b10255419293838b629d3af057f80dd`，run https://github.com/js051/open-eos-control/actions/runs/39232965133 。安全／Core／建置與隔離驗證通過，App **825/825**；UI 43 案剩 **1 案／1 斷言失敗**。direct 控制完整往返、日期雙語 XXXL、大字級 metadata、真 OS picker 取消重開與六種上傳組合全部通過。
- 唯一失敗為 canonical English 錄影旅程的 Video selected gate，尚未送 mode mutation。bounded 圖／幾何顯示單次 tap 前 picker y=551.33、高31pt，Apply 不存在；tap 後 picker y=513、Photo 仍 selected，Apply 出現且 disabled。可確證條件式插入 Apply 讓底部對齊的 picker 上移38.33pt；此 reflow 是否造成原生選段回復仍是待驗推論。
- 候選固定保留 Apply 的 layout slot。無 draft 時同時 hidden／不可命中／disabled／不在 AX，action 仍先 guard draft；原 revision、editor owner、session owner 與單次 mutation 路徑保留。原真 UI 選段／Apply／mode count gates 不改，新增無 draft 無可存取 Apply 與選段前後 picker y 不移動斷言。沒有額外重 tap 或等待預算變更。
- 原 #129 增加只引用 concrete Apple port 型別與 protocol conformance 的 App SDK probe，絕不建立 adapter、discovery、授權提示或相機命令。若 SDK 不提供 ImageCaptureCore 則明確 XCTSkip；編譯／連結證據不能當 USB DeviceInfo 或實體裝置功能完成。
- 第十七輪 bounded artifact `12179493135`，5,035,429 bytes，SHA256 `bd4947e5794d1da0f565a2754c2494b53da1bab684da99cfb79e98df53126907`。第十八輪候選尚待真 Apple 原生驗證，沒有全案完成或三端對齊宣告。
