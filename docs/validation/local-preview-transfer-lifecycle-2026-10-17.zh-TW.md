# G07／G09 既有流程中斷修正（2026-10-17 UTC）

基線共同 dev `ffaa42919a85c14eed2bd6b8e054a64dc1b93be9`；原 89 列中的 G07／123（PC local UVC/HDMI preview）及 G09／76（Media download）。這是現有流程的可重現錯誤修正，不新增 transport、相機能力或發布產品。

## G07：預覽啟動不可被裝置列舉綁住

production `startLocalVideo` 已成功開啟／播放 stream 後，仍 await `enumerateDevices` 才清 busy；裝置列舉若慢或卡住，畫面已播放但 Stop、來源與裝置選擇都被 disabled。列舉晚回覆亦可能在停止、track ended、斷線後宣告啟動；重疊列舉可讓舊清單或舊錯誤覆蓋新結果。

修正讓播放成功即解鎖與顯示啟動，裝置名稱更新成為獨立非阻塞工作；每次列舉以 request identity＋localVideoGeneration 驗證回覆。Stop/source change/disconnect 的既有 generation 退休契約保留，不把其他相機操作的責任取消。

新測試直接擷取 production functions，使用合成 DOM／MediaStream／deferred promise；初始 7 案基線 6 fail／1 pass，皆為產品斷言。最終 8 案涵蓋 stop、disconnect、實際註冊的 track-ended callback、新 session、重疊列舉成功／錯誤、正常啟動，以及第二次 start 真正 pending 時舊列舉不得解鎖或停止新 stream。review 指出的 Stop 鎖定與較新操作回饋覆寫已一併修正。

## G09：目的地失敗仍須取消來源 stream

`media-transfer.readResponse` 原來只在 AbortSignal 取消 reader；目的地 writeChunk、progress callback 或長度驗證失敗僅 releaseLock，來源串流仍可存活。新的錯誤 cleanup 至多 cancel 一次並釋放 reader，取消失敗或卡住不掩蓋原始錯誤、不阻塞錯誤回報；正常 EOF 不額外取消。

回歸涵蓋寫入、progress（含初始／最終）、長度、read 失敗、預先取消、cancel/release 異常、cancel 永不完成、既有正常 abort／成功 EOF。使用真 JavaScript ReadableStream 與可控制 reader，未使用真相機／OS目的地。

## 驗證與交付界線

- `npm --prefix bridge run test:modules` exit 0；所有 Node runner 摘要合計 **3619 pass／0 fail／0 cancelled／0 skipped**，另含既有直接 assert 腳本。此數字是本次 script 執行數，不是 3619 個不重複產品功能或三端覆蓋率。
- 最後補強 track-ended listener 的 8 案再跑通過；production source 未改動。
- 相鄰 RTP owner 測試原本的合成 DOM 缺少 `replaceChildren`（上一批設定 reset 已使用它），造成 12 個 fixture TypeError。只補該 DOM API noop，未減斷言；44 案恢復通過。
- `node --check` 兩個產品檔、`git diff --check` 通過。
- 本批 browser GUI／真 UVC／真相機／Android／iOS native 均 **NOT_RUN**。既有 Firefox 證據不拿來代稱本批已驗。
- 初次正常 pre-commit hook 因缺 PowerShell exit 1，沒有繞 hook。依既有工具授權從 Microsoft 官方 PowerShell GitHub stable v7.6.7 取得 linux-x64 archive，官方 SHA256 `6896f9e3a6b0a354bf470835a96da76d2f801cd50dcf047ad7c4563bf93bf431` 校驗通過，僅解壓專案工具目錄；XDG cache/config/data 指向既有專案工具目錄。交付仍須原 pre-commit／pre-push hooks、非 force dev push 與 exact SHA 讀回；不改 main/release。
- Release assessment：patch（既有操作生命週期修正）；不是發布批准，不宣稱 PR ready／native parity。
