# iOS 原檔回應完整性與有界讀取

本批接續原 P2 原檔交付，延續同一 iOS 草稿 #236。來源基底 db70e5986d3256fb030211fd07bd5da0499273a3，tree555731ba76734a4e05283ad16196d03b994bc2ca，與本地已審 a1cbfe58 相同。這是批次原檔工作流之前的必要傳輸基礎，不包含多選、批次分享或外部目的地驗收。

## 來源發現與修正

原 CCAPI／Bridge 原檔下載接受任意 2xx，未在發佈檔案前明確拒絕 206、Content-Range 或可比較的 Content-Length 不符；CCAPI 的 Data(contentsOf:).prefix(2000) 也先讀整份原檔才截取前綴。這些缺口來自 production 來源與新受控測試來源，尚未在本地 Swift runtime 執行 red→green。

- 共用既有 HTTP framing parser，同時驗 Data 與 file-backed response。URLSession 保留暫存檔前先驗檔案型態、大小與可比較的 Content-Length。
- 原檔發佈要求 HTTP 200、沒有 Content-Range、非空 regular file；對 partial／framing 錯誤立即失敗，不重新嘗試其他原檔 variant。
- 保留 gzip／Transfer-Encoding 已解碼後不能直接比較線上長度的既有語意。listing 的 item.sizeBytes 沒有版本／新鮮度保證，只作進度提示，不能當作來源 checksum。
- CCAPI 保留非成功 HTTP／文字回應的既有 main variant fallback。取消不合作的 transport 回覆後，以及 move 前，都再次核取消；所有拒絕路徑都嘗試移除本次暫存，不刪既存目的檔。
- 前綴改為 FileHandle 有界讀取，不把大型原檔整份載入記憶體。

## 測試與限制

新增 12 個 Core 測試：9 個 client 控制、3 個 file helper 控制。涵蓋 206／Content-Range、短或超長及無效／衝突 Content-Length、空檔、gzip／Transfer-Encoding 正向控制、過期 listing 大小提示、404→文字→type=main fallback、取消後 binary／text／404 回覆、暫存清理與既存目的檔。4 GiB sparse file 控制只寫入 4,000 bytes 並讀 2,000 bytes，不建立 4 GiB Data。

獨立來源審查沒有阻擋項，git diff --check 通過。本地沒有 Swift，baseline 與 fixed 命令均為 swift: command not found，因此編譯與 RED/GREEN 一律 NOT_RUN，必須以此批 exact-head 正常 CI 為準。長度與完整 HTTP 回應不是來源 hash、格式可解碼、硬體耐久性或外部儲存提供者成功證據。

## 同批保留的 UI 失敗與修正

前版 db70e598 的 CI38088778129：Core265、App234通過，UI23項1失敗。日期／評分／資料夾雙語旅程通過127.802秒，空清單仍可清資料夾通過32.530秒；不能把22個UI通過當作整體綠燈。

唯一失敗為 testOfflineMediaDeletionRequiresConfirmation:484，初次開相簿後只等第二列 actions 可互動，沒有滾動。新增工具列使原首屏可點前提不可靠。最小來源修正沿既有 scrollToInteraction，維持同5秒期限與全部刪除確認／刪除後不存在 assertions。修正後 runtime 尚待正常 CI，不以來源推斷代替結果。

該失敗 artifact11683054559，148439908bytes，SHA256 ea36856daa2834496f012bc22d122c7c72cd1b9c55a143578a86da248a38f0c6。更早 rating head864038e 的1/21 UI失敗仍保留，不倒推原平台根因。

## Release Assessment

最新已發布為 v0.12.0 Development Preview。原檔完整性修正本身為 patch；同 #236 新挑片功能整批影響 minor。維持開發草稿，不合併 main、不發版；release HOLD 與完整 App 集中驗收狀態不變。沒有實體 iPhone／相機、外部保存目的地或批次交付完成證據。
