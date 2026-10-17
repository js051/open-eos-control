# PC LUT 控制消失後的鍵盤接續

原 G07/#62、G13/#122。Firefox baseline產品`7711a6dff2650f1d1569fc910c6a42c623c19326`已以原生picker載入自有identity.cube，Tab到移除、Enter後按鈕隱藏而焦點掉BODY；再按Enter無法接續載入。21:17:08 terminal，3圖與native焦點事件保留。

修正僅於renderMonitoringControls將目前focused remove/cancel隱藏時，仍同開啟dialog、沒有其他dialog或新焦點，且Load仍連接/可見/可用，才將焦點回Load。保留LUT資料、匯入queue、舊read retirement與錯誤語意。

production handler＋模擬DOM焦點eviction：基準54 pass／3 fail，固定58/58，涵蓋remove、cancel、read settlement與closed/new focus/detached/disabled/hidden/no rect/other dialog隔離。模擬DOM反例與真Firefox基準分開，不宣稱screen reader、GPU色彩或手機驗收。獨立窄審無blocker，另補同步blur期間新焦點不搶回。完整回歸與固定版Firefox另記。

固定版產品 `5c27ee09c5b90e50f8f86fa358220985b3e2454e`：2026-10-17 21:24 UTC，同原生 picker 載入 SYNTHETIC IDENTITY 2³，Tab→Remove→Enter 後焦點回 Load；再 Enter 確實開啟原生 picker，再以 Escape 取消。21:24:38 自有 Firefox terminal。公開[基準與固定證據](evidence/pc-monitor-lut-keyboard-2026-10-17/)包括焦點事件、產品 SHA、合成 fixture 與畫面；重新開啟 picker 的本地截圖含共用目錄名稱，因此不對外上傳。

完整 Node modules 3823 passed、static UI 15/15、focused 58/58，syntax/diff clean。取消 pending import 與 read settlement 的焦點覆蓋為 host 測試；本次真正 Firefox 僅驗證已載入 LUT 移除的鍵盤接續，沒有擴張為全部 LUT／GPU 色彩或三端驗收。
