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
