# 2026-10-16 P2/G08 gphoto 漸進清單／取消原生 Firefox 窄驗收

受驗 source：`2fbeeeb80a8130c35d5b961dfa51484cc7f15d58`。原生雲端 Firefox 157.0.1 / BuildID 20261005135250，normal UI 與新獨立 profile；未改 production。

## 真資料路徑與 fixture

`Media UI → Bridge NDJSON → GPhoto2Session.list_media_progressive → SubprocessGPhotoRunner → gphoto_listing.py → 既有 tests/fixtures/gphoto_listing_peer.py`。

測試 wrapper 只替換 FakeRunner 的 run_listing，呼叫 production subprocess runner。CLI 真正以 pipe 輸出首筆（包含拆開的 UTF-8 字元），然後等每次獨立 attempt 目錄的 release marker。不是 JS 假 response，也不是瞬間成功後冒稱取消。其他 USB 指令保持 FakeRunner；沒有真 USB/相機、外部服務或新工具。

## 實際 UI 與子程序終態

| 步驟 | 實際證據 |
|---|---|
| 開 Media / Recent | 顯示 Loading camera media，1 item received；Cancel loading 可操作 |
| UI Cancel loading | 顯示 Loading cancelled，1 item kept；沒有顯示完整清單成功 |
| attempt 1 真 CLI | PID18586，08:03:57.950 開始，08:04:09.436 終態 MEDIA_LIST_CANCELLED；回傳時 childStillExists=false |
| 明確選 Full card | 新 attempt 2，08:04:24.532 開始，先顯示1筆載入中；證明前次取消後可重新取得工作權 |
| 釋放 attempt 2 fixture | 08:04:36.856 真 CLI COMPLETE，PID18598已不存在 |
| 完整 UI | 1101 media items；1–72 of1101；切下一頁顯示73–144 of1101 |
| 收尾 | UI斷線，08:05:07.818受控關閉browser/fixture；08:05:16核六個owned PID全部退出 |

原生程序 receipt 即時可能看到短暫退出中的 child；後續 ps 獨立核對已全退出。兩個 listing child 在各自 runner 回傳時均已 reap。

四張持久截圖分別保存 partial、cancelled、complete1101、page2。機器驗收收據 SHA256：`91fbb90ea7e702442af39c02d150388325ca9d5da23faf11c70e65df5f79a07c`。

最低可用磁碟4,399,149,056 bytes，未觸4GiB底線或提前停止guard。50ms抽樣監控不是kernel quota。可重建新profile關閉後已清理；原始碼、fixture、所有驗收證據保留。

## 判定與界線

本受控漸進清單／取消／新attempt完成的 PC 原生 Firefox UI 窄流程 PASS。取消畫面保留通用「相機讀取可能繼續」提醒；本次另以production subprocess runner實際PID證據確認CLI已停止，沒有把通用提醒改成未證承諾。

不涵蓋真相機/libgphoto相容性、Windows/WSL、iOS/Android原生、CCAPI分頁取消、縮圖/原始檔內容或匯入下載，也不等於89列runtime全驗。既有host取消/error/timeout測試沒有在此批重跑。沒有新發布包。
