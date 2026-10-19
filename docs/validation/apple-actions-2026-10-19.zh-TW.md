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
