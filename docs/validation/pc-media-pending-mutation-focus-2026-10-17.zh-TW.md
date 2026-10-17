# 媒體 metadata pending 關窗後鍵盤接續

原 G10/G13 #56/#122，基準 dev `ee86003d48bcccaf6eb00c1b1bbef487b71d9e03`。不新增 metadata 能力或改 mutation 收據語意。

## Firefox 基準

production HTML/JS＋合成 CCAPI-capable Bridge metadata endpoint：Details 按評分3、PUT pending 時 Close 可用，Escape 後才釋放匹配同ID評分收據。寫入結果 confirmed，但最後卡片重繪讓焦點掉 BODY，Enter 不能重開。20:39:07 terminal，3圖與事件保留；不是相機驗證。

## 修正

關閉 Details 時，只有該 session 的 mutation queue 包含原卡片ID且關窗焦點合格，才把 return target 存入這個 mutation owner。該 operation finally 重繪後一次性消費；要求相同 Details generation 與 interaction generation，再沿既有 session、view、同ID角色、可見可用按鈕、沒有新dialog／新焦點規則恢復。不取消write、不丟收據、不重送。

## Host 驗證

新增 pending 路徑基準 40 pass／1 fail（實際焦點未還回）；修正後包含不同 session/editor/generation/focus/dialog/view/刪除項目的隔離，以及不恢復／無關項目／不同session／外部焦點不取得 return target。相關兩組67/67，static UI15/15。完整 modules 與固定版 Firefox另記；不代表 Android/iOS、screen reader 或真相機完成。

## 固定版第一次實看與可見性補正

20:46 固定版 `08dc3ac8` 已回同 ID/manage 且 Enter 重開評分3；但新結果面板把卡片推出視窗，既有 preventScroll 讓焦點不可見。僅本條 deferred mutation return 允許瀏覽器隨 focus 顯示卡片；一般dialog關閉規則不變。新增選項控制基準45 pass/1 fail，修正後相關68/68。最終視覺對照另記，不把第一次圖片宣稱完全可見。

## 最終 Firefox 與整批驗證

20:51:42 terminal，產品 source `6fd825c5da550528bdb7b44ffab0cef8d72a6a79`：pending 評分3、Escape、匹配 receipt 後焦點為目前 synthetic-photo/manage；卡片區域隨原生 focus 捲入視窗，Enter 重開 Details 保留評分3。合成 fixture 底列覆蓋畫面最下緣，因此不把圖片宣稱每個卡片控制都完全無遮擋。三組[基準與固定版證據](evidence/pc-media-pending-focus-2026-10-17/assessment.json)包含前後圖片、事件、source、terminal receipt 與 fixture。

最終 modules **3812/3812**、static UI **15/15**、相關兩組 **68/68**；JavaScript syntax 與 diff check 通過。這只補 PC metadata 關窗鍵盤接續，不代表其他平台、screen reader、相機或全部89列完成。
