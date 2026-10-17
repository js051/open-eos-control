# 媒體 metadata pending 關窗後鍵盤接續

原 G08/G13 #56/#122，基準 dev `ee86003d48bcccaf6eb00c1b1bbef487b71d9e03`。不新增 metadata 能力或改 mutation 收據語意。

## Firefox 基準

production HTML/JS＋合成 CCAPI-capable Bridge metadata endpoint：Details 按評分3、PUT pending 時 Close 可用，Escape 後才釋放匹配同ID評分收據。寫入結果 confirmed，但最後卡片重繪讓焦點掉 BODY，Enter 不能重開。20:39:07 terminal，3圖與事件保留；不是相機驗證。

## 修正

關閉 Details 時，只有該 session 的 mutation queue 包含原卡片ID且關窗焦點合格，才把 return target 存入這個 mutation owner。該 operation finally 重繪後一次性消費；要求相同 Details generation 與 interaction generation，再沿既有 session、view、同ID角色、可見可用按鈕、沒有新dialog／新焦點規則恢復。不取消write、不丟收據、不重送。

## Host 驗證

新增 pending 路徑基準 40 pass／1 fail（實際焦點未還回）；修正後包含不同 session/editor/generation/focus/dialog/view/刪除項目的隔離，以及不恢復／無關項目／不同session／外部焦點不取得 return target。相關兩組67/67，static UI15/15。完整 modules 與固定版 Firefox另記；不代表 Android/iOS、screen reader 或真相機完成。
